package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class ClientConfigsTest {
    @Test fun `request uses one API prefix actual app identity and merged exact keys`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        server.createContext("/api/v1/client/configs") { exchange ->
            paths += exchange.requestURI.path + "?" + URLDecoder.decode(exchange.requestURI.rawQuery, StandardCharsets.UTF_8)
            auth += exchange.requestHeaders.getFirst("Authorization")
            assertEquals("GET", exchange.requestMethod)
            assertEquals("com.zorv.app", exchange.requestHeaders.getFirst("X-Package-Name"))
            assertEquals("android", exchange.requestHeaders.getFirst("X-Platform"))
            assertEquals("2.0.0", exchange.requestHeaders.getFirst("X-App-Version"))
            assertEquals("device-1", exchange.requestHeaders.getFirst("X-Device-ID"))
            val response = """{"code":0,"message":"success","data":{"package_name":"com.zorv.app","platform":"android","config_keys":["client.common.about","client.appsflyer.purchase.report"],"configs":{"client.appsflyer.purchase.report":true}}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            for (base in listOf("http://127.0.0.1:${server.address.port}", "http://127.0.0.1:${server.address.port}/api/v1/")) {
                val client = AchatBackendClient(AchatBackendConfiguration(base, "com.zorv.app", "2.0.0"))
                val result = client.clientConfigs(listOf("client.common.about", ClientConfigKeys.PURCHASE_REPORT,
                    ClientConfigKeys.PURCHASE_REPORT), token = if (paths.isEmpty()) "saved-token" else null, deviceId = "device-1")
                assertEquals(true, result.boolean(ClientConfigKeys.PURCHASE_REPORT))
            }
            assertEquals(listOf(
                "/api/v1/client/configs?package_name=com.zorv.app&platform=android&keys=client.common.about,client.appsflyer.purchase.report",
                "/api/v1/client/configs?package_name=com.zorv.app&platform=android&keys=client.common.about,client.appsflyer.purchase.report",
            ), paths)
            assertEquals(listOf("Bearer saved-token", null), auth)
            assertEquals("client.appsflyer.purchase.report", ClientConfigKeys.PURCHASE_REPORT)
        } finally { server.stop(0) }
    }

    @Test fun `only JSON boolean is accepted and invalid business response is not a successful empty config`() {
        val prefix = """{"code":0,"data":{"package_name":"yumo.achat.app","platform":"android","config_keys":[],"configs":{COMPONENT}}}"""
        for ((raw, expected) in listOf("true" to true, "false" to false, "null" to null,
            "\"true\"" to null, "1" to null, "{}" to null)) {
            val result = parseClientConfigs(prefix.replace("COMPONENT", "\"client.appsflyer.purchase.report\":$raw"),
                "yumo.achat.app", "android")
            assertEquals(expected, result.boolean(ClientConfigKeys.PURCHASE_REPORT))
        }
        val missing = parseClientConfigs(prefix.replace("COMPONENT", ""), "yumo.achat.app", "android")
        assertNull(missing.boolean(ClientConfigKeys.PURCHASE_REPORT))
        for (raw in listOf("""{"code":1,"data":{}}""", """{"code":0,"data":null}""",
            prefix.replace("yumo.achat.app", "other.app"), prefix.replace("\"configs\":{COMPONENT}", "\"configs\":null"))) {
            assertThrows(Exception::class.java) { parseClientConfigs(raw, "yumo.achat.app", "android") }
        }
    }
}
