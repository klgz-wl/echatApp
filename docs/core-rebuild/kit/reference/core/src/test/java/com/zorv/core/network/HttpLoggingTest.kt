package com.zorv.core.network

import com.zorv.core.config.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import timber.log.Timber
import java.io.IOException

class HttpLoggingTest {
    private fun config(enabled: Boolean) = CoreRuntimeConfig(
        NetworkConfig("https://example.test/", "wss://example.test/", "https://example.test/"),
        StorageConfig("database", "preferences"), DiagnosticsConfig(enabled),
    )

    private fun capture(block: (MutableList<String>) -> Unit) {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertEquals("OkHttp", tag)
                messages += message
            }
        }
        Timber.plant(tree)
        try { block(messages) } finally { Timber.uproot(tree) }
    }

    @Test fun `输出请求响应和业务错误但脱敏凭据且不改变实际报文`() = capture { messages ->
        val requestBody = """{"username":"private-account","password":"private-password","quality":"fast"}"""
        val responseBody = """{"code":400101,"message":"Insufficient balance","token":"private-token","data":{"diamond_cost":20}}"""
        val client = OkHttpClient.Builder().addInterceptor(NetworkModule.logging(config(true)))
            .addInterceptor { chain ->
                assertEquals("Bearer private-auth", chain.request().header("Authorization"))
                assertEquals(requestBody, Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(402).message("Payment Required")
                    .header("Set-Cookie", "private-response-cookie")
                    .body(responseBody.toResponseBody("application/json".toMediaType())).build()
            }.build()
        val request = Request.Builder().url("https://example.test/tasks?device_id=private-device&page=1")
            .header("Authorization", "Bearer private-auth").header("Cookie", "private-cookie")
            .header("X-AF-UID", "private-af").header("X-Device-ID", "private-device")
            .header("X-APP-USER-ID", "private-user").post(requestBody.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { assertEquals(responseBody, it.body!!.string()) }
        val output = messages.joinToString("\n")
        assertTrue(output.contains("--> POST https://example.test/tasks"))
        assertTrue(output.contains("<-- 402"))
        assertTrue(output.contains("Insufficient balance"))
        assertTrue(output.contains("\"quality\":\"fast\""))
        assertTrue(output.contains("\"diamond_cost\":20"))
        assertTrue(output.contains("page=1"))
        assertTrue(output.contains("[REDACTED]"))
        assertFalse(output.contains("private-"))
    }

    @Test fun `关闭日志时请求正常且不输出任何消息`() = capture { messages ->
        val client = OkHttpClient.Builder().addInterceptor(NetworkModule.logging(config(false)))
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build() }.build()
        client.newCall(Request.Builder().url("https://example.test/").build()).execute().use { assertEquals(200, it.code) }
        assertTrue(messages.isEmpty())
    }

    @Test fun `网络失败保留诊断信息并继续抛出原异常`() = capture { messages ->
        val failure = IOException("连接测试失败")
        val client = OkHttpClient.Builder().addInterceptor(NetworkModule.logging(config(true)))
            .addInterceptor { throw failure }.build()
        try { client.newCall(Request.Builder().url("https://example.test/").build()).execute(); fail() }
        catch (error: IOException) { assertSame(failure, error) }
        assertTrue(messages.any { it.contains("HTTP FAILED") && it.contains("连接测试失败") })
    }

    @Test fun `关闭脱敏后公共日志原样输出头部查询和正文`() = capture { messages ->
        val runtime = config(true).copy(diagnostics = DiagnosticsConfig(true, redactHttpLogs = false))
        val body = """{"password":"test-password","order_id":"test-order","token":"test-token"}"""
        val client = OkHttpClient.Builder().addInterceptor(NetworkModule.logging(runtime))
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Set-Cookie", "test-response-cookie")
                .body(body.toResponseBody("application/json".toMediaType())).build() }.build()
        client.newCall(Request.Builder().url("https://example.test/?device_id=test-device")
            .header("Authorization", "Bearer test-auth").header("Cookie", "test-cookie")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use {
            assertEquals(body, it.body!!.string())
        }
        val output = messages.joinToString("\n")
        assertTrue(output.contains(body))
        for (value in listOf("Bearer test-auth", "test-cookie", "test-response-cookie", "device_id=test-device"))
            assertTrue(output.contains(value))
        assertFalse(output.contains("[REDACTED]"))
    }

    @Test fun `参考脱敏规则支持嵌套字段大小写和转义值`() {
        val output = sanitizeHttpLogMessage("""{"data":{"accessToken":"private-access","refresh_token":"private-refresh","PASSWORD":"private-\"escaped","user_id":12345},"status":"processing"}""")
        assertFalse(output.contains("private-"))
        assertFalse(output.contains("12345"))
        assertTrue(output.contains("\"status\":\"processing\""))
    }
}
