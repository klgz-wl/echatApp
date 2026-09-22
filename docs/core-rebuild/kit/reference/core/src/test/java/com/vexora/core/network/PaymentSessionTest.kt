package com.vexora.core.network

import com.vexora.core.auth.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.io.IOException

class PaymentSessionTest {
    private class Fixture(val refreshStatus: Int = 200) {
        val sessions = SessionCoordinator(MemoryStorage())
        var refreshes = 0
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val api = Retrofit.Builder().baseUrl("https://business.example/api/v1/")
            .client(OkHttpClient.Builder().addInterceptor { chain ->
                refreshes++
                assertEquals("/api/v1/auth/refresh", chain.request().url.encodedPath)
                response(chain.request(), refreshStatus, """{"code":0,"data":{"token":"new-token"}}""")
            }.build()).addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build().create(PublicAuthApi::class.java)
        suspend fun login() = sessions.saveLogin(AuthResponse("old-token", "refresh", "user"))
        fun client(payment: Boolean = true, retryStatus: Int = 401, logging: Boolean = false): OkHttpClient {
            val base = if (payment) paymentClient(OkHttpClient(), sessions, api, logging, 30, Json)
                else OkHttpClient.Builder().addInterceptor(SessionInterceptor(sessions, api)).build()
            return base.newBuilder().addInterceptor { chain ->
                requests += chain.request()
                bodies += chain.request().body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty()
                response(chain.request(), if (requests.size == 1) 401 else retryStatus, """{"error":"invalid authorization token"}""")
            }.build()
        }
        fun request(operation: String = "initialize") = Request.Builder()
            .url("https://payment.example/payment-api/v1/client/payments/$operation")
            .tag(Session::class.java, sessions.current!!)
            .post("""{"order_id":"same-order"}""".toRequestBody("application/json".toMediaType())).build()
    }

    @Test fun `支付初始化查单和事件重试401均保留刷新后的主会话`() = runBlocking {
        for (operation in listOf("initialize", "same-order/status", "same-order/client-events")) {
            val f = Fixture(); f.login(); val epoch = f.sessions.current!!.epoch
            f.client().newCall(f.request(operation)).execute().use { assertEquals(401, it.code) }
            assertEquals(1, f.refreshes)
            assertEquals(listOf("Bearer old-token", "Bearer new-token"), f.requests.map { it.header("Authorization") })
            assertEquals(1, f.bodies.distinct().size)
            assertTrue(f.bodies.all { it.contains("same-order") })
            assertEquals(epoch, f.sessions.current!!.epoch)
            assertEquals("new-token", f.sessions.current!!.token)
        }
    }
    @Test fun `支付令牌正常过期刷新后能继续原请求`() = runBlocking {
        val f = Fixture(); f.login()
        f.client(retryStatus = 200).newCall(f.request()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, f.refreshes); assertEquals(2, f.requests.size); assertNotNull(f.sessions.current)
    }
    @Test fun `主业务重试401仍清理会话`() = runBlocking {
        val f = Fixture(); f.login()
        f.client(payment = false).newCall(f.request()).execute().use { assertEquals(401, it.code) }
        assertNull(f.sessions.current)
    }
    @Test fun `真实刷新失效仍退出而刷新服务暂时故障保留会话`() = runBlocking {
        for (status in listOf(401, 403, 500)) {
            val f = Fixture(status); f.login()
            try {
                f.client().newCall(f.request()).execute().use { assertEquals(401, it.code) }
                assertTrue(status != 500)
            } catch (_: IOException) { assertEquals(500, status) }
            assertEquals(1, f.refreshes); assertEquals(1, f.requests.size)
            if (status == 500) assertNotNull(f.sessions.current) else assertNull(f.sessions.current)
        }
    }
    @Test fun `旧账号支付请求不发送新账号凭据`() = runBlocking {
        val f = Fixture(); f.login(); val request = f.request()
        f.sessions.saveLogin(AuthResponse("other-token", "other-refresh", "other-user"))
        try { f.client().newCall(request).execute().close(); fail() } catch (_: IOException) { }
        assertTrue(f.requests.isEmpty()); assertEquals(0, f.refreshes)
        assertEquals("other-user", f.sessions.current!!.userId)
    }
    @Test fun `支付诊断仅输出白名单错误且不消费响应或泄露敏感信息`() {
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logs += message }
        }
        Timber.plant(tree)
        try {
            for (body in listOf("""{"error":"invalid authorization token","payment_url":"https://pay.example/?token=private-secret"}""",
                """{"error":"private-error","token":"private-secret"}""")) {
                val client = OkHttpClient.Builder().addInterceptor(paymentDiagnostics(true, Json))
                    .addInterceptor { response(it.request(), 401, body) }.build()
                client.newCall(Request.Builder().url("https://payment.example/private-order/status?token=private-secret")
                    .header("Authorization", "Bearer private-jwt").build()).execute().use { assertEquals(body, it.body!!.string()) }
            }
            val output = logs.joinToString("\n")
            assertTrue(output.contains("auth_present=true error=invalid authorization token"))
            assertTrue(output.contains("error=unrecognized")); assertFalse(output.contains("private-"))
        } finally { Timber.uproot(tree) }
    }
    @Test fun `真实支付客户端各接口输出BODY与追踪头且不修改报文`() = runBlocking {
        captureLogs { logs ->
            val f = Fixture(); f.login()
            for ((operation, code, body) in listOf(
                Triple("initialize", 503, """{"error":"PAYMENT_CHANNEL_UNAVAILABLE"}"""),
                Triple("private-order/client-events", 400, """{"error":"channel_code is required"}"""),
                Triple("private-order/status", 200, """{"data":{"status":"paid","fulfillment_status":"fulfilled"}}"""),
                Triple("initialize", 200, """{"data":{"payment_url":"https://pay.example/private-link","channel_type":"third_party","sdk_params":{"client_secret":"private-secret"}}}"""),
            )) {
                val client = paymentClient(OkHttpClient(), f.sessions, f.api, true, 30, Json).newBuilder()
                    .addInterceptor { chain ->
                        assertEquals("Bearer old-token", chain.request().header("Authorization"))
                        if (operation != "private-order/status")
                            assertEquals("""{"order_id":"same-order"}""", Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
                        response(chain.request(), code, body).newBuilder()
                            .header("x-request-id", "request-trace").header("x-trace-id", "business-trace")
                            .header("Set-Cookie", "private-cookie").header("Location", "https://pay.example/private-location").build()
                    }.build()
                val request = f.request(operation).let { if (operation.endsWith("status")) it.newBuilder().get().build() else it }
                client.newCall(request).execute().use { assertEquals(body, it.body!!.string()) }
                assertFalse(client.retryOnConnectionFailure)
                assertFalse(client.followRedirects)
            }
            val output = logs.joinToString("\n")
            assertTrue(output.contains("--> POST https://payment.example/payment-api/v1/client/payments/initialize"))
            assertTrue(output.contains("--> GET https://payment.example/payment-api/v1/client/payments/[REDACTED]/status"))
            assertTrue(output.contains("/client/payments/[REDACTED]/client-events"))
            assertTrue(output.contains("<-- 503")); assertTrue(output.contains("<-- 400"))
            assertTrue(output.contains("channel_code is required"))
            assertTrue(output.contains("-> 200 auth_present=true error=none"))
            assertTrue(output.contains("x-request-id: request-trace")); assertTrue(output.contains("x-trace-id: business-trace"))
            assertTrue(output.contains("\"order_id\":\"[REDACTED]\""))
            assertTrue(output.contains("\"fulfillment_status\":\"fulfilled\""))
            assertFalse(output.contains("private-")); assertFalse(output.contains("old-token")); assertFalse(output.contains("same-order"))
        }
    }

    @Test fun `支付401每次实际尝试均打印且关闭配置后静默`() = runBlocking {
        for (enabled in listOf(true, false)) captureLogs { logs ->
            val f = Fixture(); f.login()
            f.client(logging = enabled).newCall(f.request()).execute().close()
            assertEquals(2, f.requests.size)
            if (enabled) {
                assertEquals(2, logs.count { it.startsWith("--> POST https://payment.example/") })
                assertEquals(2, logs.count { it.startsWith("<-- 401") })
                assertFalse(logs.joinToString().contains("old-token")); assertFalse(logs.joinToString().contains("new-token"))
            } else assertTrue(logs.isEmpty())
        }
    }

    @Test fun `支付网络异常也输出失败且不改变异常和会话`() = runBlocking {
        captureLogs { logs ->
            val f = Fixture(); f.login()
            val failure = IOException("connection to https://pay.example/private-link failed")
            val client = paymentClient(OkHttpClient(), f.sessions, f.api, true, 30, Json).newBuilder()
                .addInterceptor { throw failure }.build()
            try { client.newCall(f.request()).execute().close(); fail() }
            catch (error: IOException) { assertSame(failure, error) }
            assertNotNull(f.sessions.current)
            assertTrue(logs.any { it.contains("HTTP FAILED") })
            assertFalse(logs.joinToString().contains("private-link"))
        }
    }

    @Test fun `支付正文的嵌套和转义URL也不泄露收银台凭据`() {
        val output = sanitizePaymentHttpLogMessage("""{"data":{"paymentUrl":"https://pay.example/private-first","other":"https:\/\/pay.example/private-second","signature":"private-sign","access_token":"private-access"}}""")
        assertFalse(output.contains("private-"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test fun `测试环境支付关闭脱敏后保留完整报文且关闭日志仍不输出`() = runBlocking {
        for (enabled in listOf(true, false)) captureLogs { logs ->
            val f = Fixture(); f.login()
            val body = """{"order_id":"test-order","payment_url":"https://pay.example/?token=test-link","sdk_params":{"client_secret":"test-secret"}}"""
            val client = paymentClient(OkHttpClient(), f.sessions, f.api, enabled, 30, Json, redact = false)
                .newBuilder().addInterceptor { chain -> response(chain.request(), 200, body).newBuilder()
                    .header("Location", "https://pay.example/?token=test-location")
                    .header("Set-Cookie", "test-cookie").build() }.build()
            client.newCall(f.request("same-order/client-events")).execute().use { assertEquals(body, it.body!!.string()) }
            if (enabled) {
                val output = logs.joinToString("\n")
                for (value in listOf("Bearer old-token", "/client/payments/same-order/client-events",
                    "\"order_id\":\"same-order\"", body, "https://pay.example/?token=test-location", "test-cookie"))
                    assertTrue(output.contains(value))
                assertFalse(output.contains("[REDACTED"))
            } else assertTrue(logs.isEmpty())
        }
    }

    private suspend fun captureLogs(block: suspend (MutableList<String>) -> Unit) {
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertEquals("OkHttp", tag); logs += message
            }
        }
        Timber.plant(tree)
        try { block(logs) } finally { Timber.uproot(tree) }
    }

    companion object {
        private fun response(request: Request, status: Int, body: String) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
