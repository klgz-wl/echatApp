package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerationCreationEvidenceTest {
    @Test
    fun `explicit business rejection preserves original message`() {
        withResponse(200, """{"code":400101,"message":"Insufficient diamonds"}""") { client ->
            val error = assertThrows(IllegalStateException::class.java) { create(client) }
            assertEquals("yumo.achat.core.backend.GenerationCreationRejectedException", error.javaClass.name)
            assertEquals(400101, (error as GenerationCreationRejectedException).backendCode)
            assertEquals("Insufficient diamonds", error.message)
        }
    }

    @Test
    fun `HTTP rejection retains HTTP status and supplies business cause`() {
        for (status in listOf(400, 401, 409, 503)) {
            withResponse(status, """{"code":400101,"message":"Insufficient diamonds"}""") { client ->
                val error = assertThrows(AchatBackendHttpException::class.java) { create(client) }
                assertEquals(status, error.statusCode)
                assertEquals("yumo.achat.core.backend.GenerationCreationRejectedException", error.cause?.javaClass?.name)
                assertEquals(400101, (error.cause as GenerationCreationRejectedException).backendCode)
                assertEquals("Insufficient diamonds", error.cause?.message)
            }
        }
    }

    @Test
    fun `missing task or invalid envelope does not prove rejection`() {
        val bodies = listOf(
            """{"code":0,"message":"ok"}""",
            """{"code":200,"data":{}}""",
            """{"message":"unknown"}""",
            """{"code":"400101","message":"not a numeric code"}""",
            """{"code":400101.5,"message":"not an integer code"}""",
            """{"code":null,"message":"unknown"}""",
            """{"code":true,"message":"unknown"}""",
            """{"code":4294967296,"message":"out of range"}""",
            "<html>upstream unavailable</html>",
        )
        for (body in bodies) {
            withResponse(200, body) { client ->
                val error = assertThrows(Exception::class.java) { create(client) }
                assertFalse(body, error.javaClass.simpleName == "GenerationCreationRejectedException")
            }
            for (status in listOf(409, 503)) {
                withResponse(status, body) { client ->
                    val error = assertThrows(AchatBackendHttpException::class.java) { create(client) }
                    assertEquals(status, error.statusCode)
                    assertNull(body, error.cause)
                }
            }
        }
    }

    @Test
    fun `explicit business codes preserve their code without HTTP mapping`() {
        for (code in listOf(409, 503)) {
            withResponse(200, """{"code":$code,"message":"Service refused creation"}""") { client ->
                val error = assertThrows(GenerationCreationRejectedException::class.java) { create(client) }
                assertEquals(code, error.backendCode)
                assertEquals("Service refused creation", error.message)
            }
        }
    }

    @Test
    fun `blank rejection message is preserved`() {
        withResponse(200, """{"code":400101,"message":""}""") { client ->
            assertEquals("", assertThrows(GenerationCreationRejectedException::class.java) { create(client) }.message)
        }
    }

    @Test
    fun `upload HTTP failures remain upload errors after successful local preparation`() {
        withResponse(503, """{"code":503,"message":"Upload unavailable"}""") { client ->
            val image = prepareImageUpload {
                MultipartFormData.imagePart("image", "photo.jpg", "image/jpeg", byteArrayOf(1))
            }
            val sourceError = assertThrows(AchatBackendHttpException::class.java) {
                client.uploadVisualResource("token", image)
            }
            assertEquals(503, sourceError.statusCode)
            assertNull(sourceError.cause)
            val avatar = prepareImageUpload { profileAvatarUploadParts("photo.jpg", "image/jpeg", byteArrayOf(1)) }
            val avatarError = assertThrows(AchatBackendHttpException::class.java) {
                client.uploadProfileAvatar("token", avatar)
            }
            assertEquals(503, avatarError.statusCode)
            assertNull(avatarError.cause)
        }
    }

    @Test
    fun `creation HTTP 401 with business evidence still refreshes and retries same request`() = runBlocking {
        val tokens = mutableListOf<String>()
        val keys = mutableListOf<String>()
        var refreshes = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val isRefresh = exchange.requestURI.path == "/api/v1/auth/refresh"
            val token = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val (status, body) = when {
                isRefresh -> {
                    refreshes++
                    200 to """{"code":0,"data":{"token":"fresh"}}"""
                }
                else -> {
                    tokens += token
                    keys += exchange.requestHeaders.getFirst("Idempotency-Key")
                    if (token == "Bearer token") 401 to """{"code":40101,"message":"Expired"}"""
                    else 200 to """{"code":0,"data":{"task_id":"task-1"}}"""
                }
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val store = object : AuthSessionStore {
            var saved: AuthSession? = AuthSession("user", "token", "refresh", "session", true)
            override fun deviceId() = "device"
            override fun readSession() = saved
            override fun saveSession(session: AuthSession) { saved = session }
            override fun clearSession() { saved = null }
        }
        server.start()
        try {
            val client = AchatBackendClient("http://127.0.0.1:${server.address.port}")
            val manager = AchatSessionManager(store, client)
            val task = manager.authenticated { token -> create(client, token) }
            assertEquals("task-1", task.taskId)
            assertEquals(listOf("Bearer token", "Bearer fresh"), tokens)
            assertEquals(listOf("request-1", "request-1"), keys)
            assertEquals(1, refreshes)
            assertEquals("fresh", store.saved?.token)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `success codes still parse accepted task`() {
        for (code in listOf(0, 200)) {
            withResponse(200, """{"code":$code,"data":{"task_id":"task-1","status":"queued"}}""") { client ->
                assertEquals("task-1", create(client).taskId)
            }
        }
    }

    @Test
    fun `task lookup business error is not creation rejection`() {
        withResponse(200, """{"code":400101,"message":"lookup failed"}""") { client ->
            val error = assertThrows(IllegalStateException::class.java) {
                client.visualGenerationTask("token", "task-1")
            }
            assertEquals("lookup failed", error.message)
            assertFalse(error.javaClass.simpleName == "GenerationCreationRejectedException")
        }
    }

    private fun create(client: AchatBackendClient, token: String = "token"): VisualGenerationTask = client.createVisualGenerationTask(
        token = token, modality = "image", templateId = "template-1", quality = "fast",
        resourceId = "resource-1", idempotencyKey = "request-1",
    )

    private fun withResponse(status: Int, body: String, block: (AchatBackendClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(AchatBackendClient("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }
}
