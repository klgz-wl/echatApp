package yumo.achat.app.analytics

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.analytics.ReportEventRequest

class HttpEventApiGaidTest {
    @Test fun `manual backend HTTP serialization puts GAID at top level`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var sent: JSONObject? = null
        server.createContext("/api/v1/events/report") { exchange ->
            sent = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = """{"code":0}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val api = HttpEventApi("http://127.0.0.1:${server.address.port}")
            val request = ReportEventRequest("install-device", "z_page_view", 123L, "user", "test.app", "1", "android",
                mapOf("page_name" to "home"), gaid = "12345678-1234-4234-8234-123456789abc")
            assertTrue(api.reportEvent(request).isSuccessful)
            assertEquals("12345678-1234-4234-8234-123456789abc", sent!!.getString("gaid"))
            assertFalse(sent!!.getJSONObject("parameters").has("gaid"))
            assertTrue(api.reportEvent(request.copy(gaid = null)).isSuccessful)
            assertFalse(sent!!.has("gaid"))
        } finally { server.stop(0) }
    }
}
