package yumo.achat.app.data.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AchatBackendClientProfileTest {
    @Test
    fun `profile update uses authenticated PUT with partial body`() = withServer(
        path = "/api/v1/user/profile",
        response = profileResponse("Nova Quinn", "https://cdn.example/old.webp"),
    ) { client, request ->
        val profile = client.updateUserProfile(token = "token-1", nickname = "Nova Quinn")

        assertEquals("PUT", request.get().method)
        assertEquals("Bearer token-1", request.get().authorization)
        assertEquals("application/json", request.get().contentType)
        assertEquals("Nova Quinn", JSONObject(request.get().body).getString("nickname"))
        assertEquals("Nova Quinn", profile.displayName)
    }

    @Test
    fun `avatar upload uses authenticated multipart file endpoint`() = withServer(
        path = "/api/v1/files/upload",
        response = """{"code":0,"data":{"id":"file-1","file_url":"https://cdn.example/new.webp"}}""",
    ) { client, request ->
        val upload = client.uploadProfileAvatar(
            token = "token-2",
            parts = profileAvatarUploadParts("avatar.webp", "image/webp", byteArrayOf(1, 2, 3)),
        )

        assertEquals("POST", request.get().method)
        assertEquals("Bearer token-2", request.get().authorization)
        assertTrue(request.get().contentType.startsWith("multipart/form-data; boundary="))
        assertTrue(request.get().body.contains("name=\"file\"; filename=\"avatar.webp\""))
        assertTrue(request.get().body.contains("name=\"upload_source\""))
        assertTrue(request.get().body.contains("profile"))
        assertEquals("https://cdn.example/new.webp", upload.fileUrl)
    }

    private fun withServer(
        path: String,
        response: String,
        block: (AchatBackendClient, AtomicReference<RequestSnapshot>) -> Unit,
    ) {
        val request = AtomicReference<RequestSnapshot>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            request.set(
                RequestSnapshot(
                    method = exchange.requestMethod,
                    authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                    contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    body = exchange.requestBody.bufferedReader().use { it.readText() },
                ),
            )
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(AchatBackendClient("http://127.0.0.1:${server.address.port}"), request)
        } finally {
            server.stop(0)
        }
    }

    private fun profileResponse(name: String, avatar: String): String =
        """{"code":0,"data":{"id":"user-1","nickname":"$name","avatar":"$avatar"}}"""

    private data class RequestSnapshot(
        val method: String,
        val authorization: String,
        val contentType: String,
        val body: String,
    )
}
