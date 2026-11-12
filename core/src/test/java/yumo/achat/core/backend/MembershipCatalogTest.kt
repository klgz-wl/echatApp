package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class MembershipCatalogTest {
    @Test fun membershipCatalogHasSeparateAuthenticatedQuery() {
        var query: String? = null
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/products") { exchange ->
            query = exchange.requestURI.rawQuery
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = """{"code":0,"data":{"products":[],"payment_providers":[]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            AchatBackendClient("http://127.0.0.1:${server.address.port}").membershipCatalog("test-token")
            assertEquals("product_type=membership&platform=android&location=store", query)
            assertEquals("Bearer test-token", authorization)
        } finally { server.stop(0) }
    }
}
