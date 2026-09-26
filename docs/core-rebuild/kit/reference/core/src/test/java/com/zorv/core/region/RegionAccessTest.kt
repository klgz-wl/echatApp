package com.zorv.core.region

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class RegionAccessTest {
    private val blocked = setOf("CN", "TW", "MO", "HK", "SG", "MY")
    private fun gate(local: Set<String> = emptySet(), enabled: Boolean = true, retries: Int = 0, retryDelay: Long = 0, connected: () -> Boolean = { true }, ip: suspend () -> String) =
        RegionAccess(RegionAccessConfiguration(enabled, blocked, retries, retryDelay), LocalRegionSource { local.map { LocalRegion(LocalRegionKind.SYSTEM, it) } }, IpRegionSource { ip() }, RegionNetworkSource { connected() })

    @Test fun `六地区本地任一命中均拦截且无需IP`() = runBlocking {
        for (country in blocked) {
            val error = runCatching { gate(setOf("US", country.lowercase())) { error("不得请求 IP") }.check() }.exceptionOrNull()
            assertEquals("R102", (error as RegionRestrictedException).diagnosticCode)
        }
    }
    @Test fun `六地区IP命中均覆盖本地允许信号`() = runBlocking {
        for (country in blocked) assertTrue(runCatching {
            gate(setOf("US")) { country.lowercase() }.check()
        }.exceptionOrNull() is RegionRestrictedException)
    }
    @Test fun `无SIM或本地未知仍必须有允许的IP`() = runBlocking {
        gate(setOf("", "US")) { " gb " }.check()
        gate { "US" }.check()
        for (country in listOf("", "ZZ", "unknown", "null", "123")) assertTrue(runCatching {
            gate { country }.check()
        }.exceptionOrNull() is RegionUnavailableException)
    }
    @Test fun `关闭限制不读取任何来源`() = runBlocking {
        RegionAccess(RegionAccessConfiguration(false, blocked, 5, 0), LocalRegionSource { error("不得读本地") },
            IpRegionSource { error("不得联网") }, RegionNetworkSource { error("不得检查网络") }).check()
    }
    @Test fun `网络失败不放行且用户重试重新查IP`() = runBlocking {
        var calls = 0
        val gate = gate { calls++; if (calls == 1) throw IOException() else "US" }
        assertTrue(runCatching { gate.check() }.exceptionOrNull() is RegionUnavailableException)
        gate.check()
        assertEquals(2, calls)
    }
    @Test fun `不缓存放行结果且取消正常传播`() = runBlocking {
        var country = "US"
        val gate = gate { country }
        gate.check(); country = "CN"
        assertTrue(runCatching { gate.check() }.exceptionOrNull() is RegionRestrictedException)
        assertTrue(runCatching { gate { throw CancellationException() }.check() }.exceptionOrNull() is CancellationException)
    }
    @Test fun `响应仅接受country字符串并忽略附加字段`() {
        assertEquals("US", parseCountry("""{"ip":"192.0.2.1","country":"US","extra":true}"""))
        for (body in listOf("{}", "[]", "null", "", "<html>error</html>", """{"country":null}""", """{"country":1}"""))
            assertTrue(runCatching { parseCountry(body) }.isFailure)
    }
    @Test fun `诊断保留SIM及系统来源且本地拒绝不查询IP`() = runBlocking {
        for ((kind, code) in listOf(LocalRegionKind.SIM to "R101", LocalRegionKind.SYSTEM to "R102")) {
            val access = RegionAccess(RegionAccessConfiguration(true, blocked, 5, 0),
                LocalRegionSource { listOf(LocalRegion(kind, "cn")) },
                IpRegionSource { error("本地拒绝不得联网") }, RegionNetworkSource { error("本地拒绝不得检查网络") })
            val error = runCatching { access.check() }.exceptionOrNull() as RegionRestrictedException
            assertEquals(code, error.diagnosticCode)
        }
        val error = runCatching { gate { "SG" }.check() }.exceptionOrNull() as RegionRestrictedException
        assertEquals("R103", error.diagnosticCode)
    }

    @Test fun `查询异常分类且诊断不包含原始消息`() = runBlocking {
        val failures = listOf(
            java.net.UnknownHostException("私有地址") to "R201",
            java.net.SocketTimeoutException("连接超时") to "R202",
            java.io.InterruptedIOException("timeout") to "R202",
            javax.net.ssl.SSLHandshakeException("证书原文") to "R203",
            java.net.ConnectException("连接地址") to "R204",
            IOException("响应读取失败") to "R204",
            IllegalStateException("内部原文") to "R299",
            RegionUnavailableException("R205-403") to "R205-403")
        for ((failure, code) in failures) {
            val error = runCatching { gate { throw failure }.check() }.exceptionOrNull() as RegionUnavailableException
            assertEquals(code, error.diagnosticCode)
            assertNull(error.message)
            assertNull(error.cause)
        }
    }

    @Test fun `无效国家码与无效响应诊断区分`() = runBlocking {
        val invalid = runCatching { gate { "ZZ" }.check() }.exceptionOrNull() as RegionUnavailableException
        assertEquals("R207", invalid.diagnosticCode)
        for (body in listOf("<html>error</html>", "{}", "[]", "null", "", "{\"country\":null}", "{\"country\":1}")) {
            val error = runCatching { gate { parseCountry(body) }.check() }.exceptionOrNull() as RegionUnavailableException
            assertEquals("R206", error.diagnosticCode)
        }
    }

    @Test fun `HTTP错误仅暴露状态编号成功响应正常放行`() = runBlocking {
        fun response(status: Int, body: String) = Response.Builder()
            .request(Request.Builder().url("https://example.test/").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("服务原文")
            .body(body.toResponseBody()).build()
        for (status in listOf(301, 403, 429, 500)) {
            val error = runCatching { gate { readCountryResponse(response(status, "敏感正文")) }.check() }
                .exceptionOrNull() as RegionUnavailableException
            assertEquals("R205-$status", error.diagnosticCode)
            assertNull(error.message)
        }
        gate { readCountryResponse(response(200, "{\"country\":\"US\"}")) }.check()
    }

    @Test fun `额外五次重试耗尽保留最后失败编号且手动重试重置次数`() = runBlocking {
        var calls = 0
        val access = gate(retries = 5) {
            calls++
            if (calls % 6 == 0) throw java.net.UnknownHostException() else throw IOException()
        }
        repeat(2) { round ->
            val error = runCatching { access.check() }.exceptionOrNull() as RegionUnavailableException
            assertEquals("R201", error.diagnosticCode)
            assertEquals((round + 1) * 6, calls)
        }
    }

    @Test fun `第六次恢复正常才放行有效受限IP不继续重试`() = runBlocking {
        var calls = 0
        gate(retries = 5) { calls++; if (calls < 6) throw IOException() else "US" }.check()
        assertEquals(6, calls)
        calls = 0
        val error = runCatching {
            gate(retries = 5) { calls++; if (calls == 1) throw IOException() else "CN" }.check()
        }.exceptionOrNull() as RegionRestrictedException
        assertEquals("R103", error.diagnosticCode)
        assertEquals(2, calls)
    }

    @Test fun `HTTP解析与地区码失败均重试并可恢复`() = runBlocking {
        var calls = 0
        gate(retries = 5) {
            when (++calls) {
                1 -> throw RegionUnavailableException("R205-503")
                2 -> parseCountry("<html>error</html>")
                3 -> "ZZ"
                else -> "US"
            }
        }.check()
        assertEquals(4, calls)
    }

    @Test fun `等待重试时取消停止后续请求`() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            gate(retries = 5, retryDelay = 60_000) {
                calls++; requested.complete(Unit); throw IOException()
            }.check()
        }
        requested.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun `离线零IP请求重连手动重试正常放行`() = runBlocking {
        var connected = false
        var calls = 0
        val access = gate(retries = 5, connected = { connected }) { calls++; "US" }
        val error = runCatching { access.check() }.exceptionOrNull() as RegionOfflineException
        assertEquals("R200", error.diagnosticCode)
        assertEquals(0, calls)
        connected = true
        access.check()
        assertEquals(1, calls)
    }

    @Test fun `请求期间断网立即结束且不消耗后续重试`() = runBlocking {
        var connected = true
        var calls = 0
        val error = runCatching {
            gate(retries = 5, retryDelay = 60_000, connected = { connected }) {
                calls++; connected = false; throw IOException()
            }.check()
        }.exceptionOrNull()
        assertTrue(error is RegionOfflineException)
        assertEquals(1, calls)
    }

    @Test fun `重试前再次检测网络防止断网后发送请求`() = runBlocking {
        var networkChecks = 0
        var calls = 0
        val error = runCatching {
            gate(retries = 5, connected = { ++networkChecks < 3 }) { calls++; throw IOException() }.check()
        }.exceptionOrNull()
        assertTrue(error is RegionOfflineException)
        assertEquals(1, calls)
        assertEquals(3, networkChecks)
    }

}
