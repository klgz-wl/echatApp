package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AchatBackendRefreshTest {
    @Test
    fun `refresh posts refresh token and parses new access token`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/refresh") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            assertEquals("refresh-1", body.getString("refresh_token"))
            val response = """{"code":0,"data":{"token":"new-access"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            assertEquals("new-access", client.refreshAccessToken("refresh-1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http errors preserve status code`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/user/profile") { exchange ->
            val response = """{"code":401,"message":"expired"}""".toByteArray()
            exchange.sendResponseHeaders(401, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            val error = assertThrows(AchatBackendHttpException::class.java) {
                client.userProfile("expired-access")
            }
            assertEquals(401, error.statusCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `refresh rejects a response with an empty access token`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/refresh") { exchange ->
            val response = """{"code":0,"data":{"token":""}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            assertThrows(IllegalStateException::class.java) {
                client.refreshAccessToken("refresh-1")
            }
        } finally {
            server.stop(0)
        }
    }
}
