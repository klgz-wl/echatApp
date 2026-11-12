package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class AchatBackendClientVipTest {
    @Test fun vipInfoUsesExistingAuthenticatedEndpoint() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorized = false
        server.createContext("/api/v1/vip/info") { exchange ->
            authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer test-token" &&
                exchange.requestMethod == "GET" && exchange.requestURI.rawQuery == null
            val data = """{"code":0,"data":{"is_vip":true,"auto_renew":false}}""".toByteArray()
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            assertTrue(AchatBackendClient("http://127.0.0.1:${server.address.port}").vipInfo("test-token").isVip)
            assertTrue(authorized)
        } finally { server.stop(0) }
    }
}
