package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchatBackendClientTemplateTest {
    @Test
    fun `video templates send hot sort and omit empty category`() = withTemplateServer { client, query ->
        client.templates(
            token = "token-1",
            modality = "video",
            sortBy = "hot",
            categoryId = null,
        )

        assertTrue(query.get().contains("sort_by=hot"))
        assertFalse(query.get().contains("category_id"))
    }

    @Test
    fun `image templates send latest sort and preserve category`() = withTemplateServer { client, query ->
        client.templates(
            token = "token-2",
            modality = "image",
            sortBy = "latest",
            categoryId = "portrait",
        )

        assertTrue(query.get().contains("sort_by=latest"))
        assertTrue(query.get().contains("category_id=portrait"))
    }

    private fun withTemplateServer(
        block: (AchatBackendClient, AtomicReference<String>) -> Unit,
    ) {
        val query = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/visual-generation/") { exchange ->
            query.set(exchange.requestURI.rawQuery.orEmpty())
            val bytes = """{"code":0,"data":{"items":[]}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(AchatBackendClient("http://127.0.0.1:${server.address.port}"), query)
        } finally {
            server.stop(0)
        }
    }
}
