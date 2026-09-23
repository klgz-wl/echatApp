package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import yumo.achat.core.attribution.LoginAttribution

class AchatBackendRefreshTest {
    @Test
    fun `anonymous login posts attribution body and AF uid header`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/anonymous") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("af-test-id", exchange.requestHeaders.getFirst("X-AF-UID"))
            assertEquals("stable-device", exchange.requestHeaders.getFirst("X-Device-ID"))
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            assertEquals("stable-device", body.getString("device_id"))
            assertEquals("test.package", body.getString("package_name"))
            assertEquals("android", body.getString("platform"))
            assertEquals("2.0.0", body.getString("version"))
            val attribution = body.getJSONObject("attribution")
            assertEquals("appsflyer", attribution.getString("attribution_source"))
            assertEquals("gaid-or-install", attribution.getString("device_id"))
            assertEquals("af-test-id", attribution.getString("af_uid"))
            assertEquals("network-1", attribution.getString("network"))
            val response = """{"code":0,"data":{"user_id":"user-1","token":"access","refresh_token":"refresh","session_id":"session-1","is_anonymous":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            val session = client.anonymousLogin(
                deviceId = "stable-device",
                packageName = "test.package",
                version = "2.0.0",
                attribution = LoginAttribution(
                    attributionSource = "appsflyer",
                    deviceId = "gaid-or-install",
                    afUid = "af-test-id",
                    network = "network-1",
                    campaign = null,
                    campaignId = null,
                    adgroup = null,
                    adgroupId = null,
                    creative = null,
                    creativeId = null,
                    channel = null,
                    country = null,
                    platform = "android",
                    appVersion = "2.0.0",
                    packageName = "test.package",
                    extraData = JsonObject(emptyMap()),
                ),
            )
            assertEquals("access", session.token)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `attribution report posts to backend attribution endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/attribution/report") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            assertEquals("stable-device", body.getString("device_id"))
            assertEquals("AppsFlyer", body.getString("attribution_source"))
            val response = """{"code":0}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            client.reportAttribution(
                JSONObject()
                    .put("device_id", "stable-device")
                    .put("attribution_source", "AppsFlyer"),
            )
        } finally {
            server.stop(0)
        }
    }

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
