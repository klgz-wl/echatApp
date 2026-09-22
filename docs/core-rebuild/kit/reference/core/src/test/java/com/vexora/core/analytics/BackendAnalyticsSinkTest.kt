package com.vexora.core.analytics

import com.vexora.core.config.ClientIdentity
import com.vexora.core.network.ApiResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

class BackendAnalyticsSinkTest {
    private val identity = ClientIdentity("test.vexora", "1.2.3", 10)

    @Test fun `真实Retrofit代理和序列化遵守参考接口契约`() = runBlocking {
        val received = CompletableDeferred<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/api/v1/events/report", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertNull(request.header("Authorization"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            received.complete(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":null}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val retrofitApi = Retrofit.Builder().baseUrl("https://example.invalid/api/v1/").client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build().create(EventApi::class.java)
        val completed = CompletableDeferred<Response<ApiResponse<Unit>>>()
        val api = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> =
                retrofitApi.reportEvent(request).also { completed.complete(it) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BackendAnalyticsSink(api, identity, { "device-test" }, 10, scope)
            sink.initialize()
            sink.identify("user-original")
            val tracker = RecordingTracker()
            tracker.paymentResult(paymentProperties("success", "google_play", "LEGACY", "home", "package", "order-test",
                "play_consumed", amount = 2.99, currency = "USD") + mapOf("af_content_id" to "sku-test"), "user-original")
            sink.event("payment_custom", tracker.events.last().values + ("event_time" to 1700000000123L))
            val body = withTimeout(5000) { received.await() }
            val response = withTimeout(5000) { completed.await() }
            assertTrue(response.isSuccessful)
            assertEquals(0, response.body()!!.code)
            assertNull(response.body()!!.data)
            assertEquals(setOf("device_id", "event_type", "event_time", "user_id", "package_name", "app_version", "platform", "parameters"), body.keys)
            assertEquals("z_payment_custom", body.getValue("event_type").jsonPrimitive.content)
            assertEquals(1700000000123L, body.getValue("event_time").jsonPrimitive.long)
            assertEquals("device-test", body.getValue("device_id").jsonPrimitive.content)
            assertEquals("user-original", body.getValue("user_id").jsonPrimitive.content)
            assertEquals(identity.packageName, body.getValue("package_name").jsonPrimitive.content)
            assertEquals(identity.versionName, body.getValue("app_version").jsonPrimitive.content)
            assertEquals("android", body.getValue("platform").jsonPrimitive.content)
            val parameters = body.getValue("parameters").jsonObject
            assertTrue(parameters.values.all { it.jsonPrimitive.isString })
            assertEquals("true", parameters.getValue("af_success").jsonPrimitive.content)
            assertEquals("2.99", parameters.getValue("af_price").jsonPrimitive.content)
            assertEquals("2.99", parameters.getValue("af_revenue").jsonPrimitive.content)
            assertEquals("USD", parameters.getValue("af_currency").jsonPrimitive.content)
            assertEquals("order-test", parameters.getValue("af_order_id").jsonPrimitive.content)
            assertEquals("sku-test", parameters.getValue("af_content_id").jsonPrimitive.content)
            assertFalse(parameters.containsKey("amount"))
        } finally {
            scope.cancel()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `排队后切换用户不改写匿名身份原用户时间和参数`() = runBlocking {
        val requests = Channel<ReportEventRequest>(Channel.UNLIMITED)
        val api = recordingApi(requests)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BackendAnalyticsSink(api, identity, { "device" }, 10, scope)
            val parameters = mutableMapOf<String, Any>("event_time" to 123L, "page_name" to "home")
            sink.event("app_launch", parameters)
            sink.identify("old-user")
            sink.event("page_view", parameters)
            parameters["page_name"] = "changed"
            sink.identify("new-user")
            sink.initialize()
            sink.initialize()
            withTimeout(5000) {
                assertNull(requests.receive().userId)
                val request = requests.receive()
                assertEquals("old-user", request.userId)
                assertEquals(123L, request.eventTime)
                assertEquals("home", request.parameters["page_name"])
                assertTrue(requests.tryReceive().isFailure)
            }
        } finally { scope.cancel() }
    }

    @Test fun `HTTP业务网络解析失败不重试且不阻断后续事件`() = runBlocking {
        val requests = Channel<ReportEventRequest>(Channel.UNLIMITED)
        val api = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                requests.send(request)
                return when (request.eventType.removePrefix("z_")) {
                    "http_failure" -> Response.error(503, "unavailable".toResponseBody())
                    "business_failure" -> Response.success(ApiResponse(400001))
                    "empty_body" -> Response.success(null)
                    "network_failure" -> throw IOException("测试断网")
                    "parse_failure" -> throw kotlinx.serialization.SerializationException("测试响应解析失败")
                    else -> Response.success(ApiResponse(0))
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BackendAnalyticsSink(api, identity, { "device" }, 10, scope)
            val names = listOf("http_failure", "business_failure", "empty_body", "network_failure", "parse_failure", "success")
            sink.initialize()
            names.forEach { sink.event(it, emptyMap()) }
            withTimeout(5000) { assertEquals(names.map { "z_$it" }, names.map { requests.receive().eventType }) }
        } finally { scope.cancel() }
    }

    @Test fun `队列满时非阻塞丢弃新事件`() = runBlocking {
        val requests = Channel<ReportEventRequest>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BackendAnalyticsSink(recordingApi(requests), identity, { "device" }, 1, scope)
            sink.event("first", emptyMap())
            sink.event("overflow", emptyMap())
            sink.initialize()
            withTimeout(5000) { assertEquals("z_first", requests.receive().eventType) }
            sink.event("last", emptyMap())
            withTimeout(5000) { assertEquals("z_last", requests.receive().eventType) }
        } finally { scope.cancel() }
    }

    @Test fun `原专属事件均使用自有平台前缀且不携带AF收入参数`() = runBlocking {
        val requests = Channel<ReportEventRequest>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sink = BackendAnalyticsSink(recordingApi(requests), identity, { "device" }, 10, scope)
            val names = listOf("purchase_client", "purchase_client_buy", "purchase_client_buy_coins",
                "3rdpayment_link_ok", "3rdpayment_link_error", "3rdpayment_page_loaded")
            sink.initialize()
            names.forEach { sink.event(it, mapOf("status" to "success")) }
            withTimeout(5000) {
                names.forEach { name ->
                    val request = requests.receive()
                    assertEquals("z_$name", request.eventType)
                    assertFalse(request.parameters.containsKey("af_revenue"))
                }
            }
        } finally { scope.cancel() }
    }

    private fun recordingApi(requests: Channel<ReportEventRequest>) = object : EventApi {
        override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
            requests.send(request)
            return Response.success(ApiResponse(0))
        }
    }
}
