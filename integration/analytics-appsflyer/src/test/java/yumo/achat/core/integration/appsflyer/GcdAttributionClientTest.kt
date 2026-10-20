package yumo.achat.core.integration.appsflyer

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GcdAttributionClientTest {
    private val config = GcdRecheckConfig(
        baseUrl = "https://gcdsdk.appsflyer.com/install_data/v4.0/".toHttpUrl(),
        timeoutMillis = 10_000,
    )

    @Test fun `request encodes package key and UID separately without business credentials`() = runBlocking {
        val factory = FakeFactory()
        val client = GcdAttributionClient(config, "app/id +", "key& value", factory)
        val pending = async { client.fetch("uid/a+b &") }
        yield()
        val request = factory.call.request()

        assertEquals("/install_data/v4.0/app%2Fid%20+", request.url.encodedPath)
        assertEquals("app/id +", request.url.pathSegments.last())
        assertEquals("key& value", request.url.queryParameter("devkey"))
        assertEquals("uid/a+b &", request.url.queryParameter("device_id"))
        assertEquals("no-cache", request.header("Cache-Control"))
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        factory.call.respond(200, """{"af_status":"Organic"}""")
        assertEquals("Organic", pending.await()?.getValue("af_status")?.jsonPrimitive?.content)
    }

    @Test fun `valid response preserves raw JSON types and invalid responses close their bodies`() = runBlocking {
        val bodies = listOf(
            200 to """{"af_status":"Non-organic","is_fb":true,"counts":[1,null]}""",
            200 to """{"af_status":"Organic"}""",
            404 to """{"af_status":"Non-organic"}""",
            200 to "",
            200 to "[]",
            200 to "{bad",
            200 to """{"af_status":"unknown"}""",
            200 to """{"media_source":"Facebook Ads"}""",
        )
        bodies.forEachIndexed { index, (status, raw) ->
            val factory = FakeFactory()
            val pending = async { GcdAttributionClient(config, "app.id", "key", factory).fetch("af-uid") }
            yield()
            val responseBody = factory.call.respond(status, raw)
            val result = pending.await()
            if (index == 0) {
                assertEquals(JsonPrimitive(true), result?.get("is_fb"))
                assertEquals("[1,null]", result?.get("counts").toString())
            } else if (index == 1) assertEquals("Organic", result?.getValue("af_status")?.jsonPrimitive?.content)
            else assertNull(result)
            assertTrue(responseBody.closed)
        }
    }

    @Test fun `coroutine cancellation cancels actual call and late response body closes`() = runBlocking {
        val factory = FakeFactory()
        val pending = async { GcdAttributionClient(config, "app.id", "key", factory).fetch("af-uid") }
        yield()
        pending.cancel()
        assertTrue(factory.call.isCanceled())
        val body = factory.call.respond(200, """{"af_status":"Non-organic"}""")
        assertTrue(body.closed)
    }

    @Test fun `canceled fetch stays incomplete until actual call callback finishes`() = runBlocking {
        val factory = FakeFactory()
        val pending = async { GcdAttributionClient(config, "app.id", "key", factory).fetch("af-uid") }
        yield()
        pending.cancel()
        yield()

        assertTrue(factory.call.isCanceled())
        assertFalse(pending.isCompleted)
        factory.call.fail(IOException("canceled"))
        pending.join()
        assertTrue(pending.isCompleted)
    }

    @Test fun `dedicated client has no redirect cache hidden retry or interceptors`() {
        val client: OkHttpClient = newGcdHttpClient(config)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(10_000, client.callTimeoutMillis)
    }

    private class FakeFactory : Call.Factory {
        lateinit var call: FakeCall
        override fun newCall(request: Request): Call = FakeCall(request).also { call = it }
    }

    private class FakeCall(private val requestValue: Request) : Call {
        private var callback: Callback? = null
        private var canceled = false
        override fun request(): Request = requestValue
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun cancel() { canceled = true }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout()
        public override fun clone(): Call = FakeCall(requestValue)

        fun respond(code: Int, raw: String): TrackingBody {
            val body = TrackingBody(raw)
            callback!!.onResponse(this, Response.Builder()
                .request(requestValue).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body).build())
            return body
        }

        fun fail(error: IOException) { callback!!.onFailure(this, error) }
    }

    private class TrackingBody(raw: String) : ResponseBody() {
        var closed = false
        private val bytes = raw.toByteArray()
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source() = buffered
    }
}
