package yumo.achat.core.attribution

import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AttributionHttpClientTest {
    private val payload = buildJsonObject {
        put("device_id", "frozen-device"); put("user_id", "frozen-user")
        put("package_name", "test.package"); put("app_version", "2.0.0")
    }

    @Test fun `HTTP success with failed business code is rejected`() = runBlocking {
        ServerSocket(0).use { server ->
            serve(server, "{\"code\":7}")
            val result = runCatching { AttributionHttpClient("http://127.0.0.1:${server.localPort}").report(payload) }
            assertTrue(result.isFailure)
        }
    }

    @Test fun `delivery uses the frozen owner and configured version headers`() = runBlocking {
        ServerSocket(0).use { server ->
            val captured = serve(server, "{\"code\":0}")
            AttributionHttpClient("http://127.0.0.1:${server.localPort}/api/v1/").report(payload)
            val headers = captured.await()
            assertTrue(headers.contains("POST /api/v1/attribution/report HTTP/1.1"))
            assertTrue(headers.contains("X-Device-ID: frozen-device"))
            assertTrue(headers.contains("X-APP-USER-ID: frozen-user"))
            assertTrue(headers.contains("X-App-Version: 2.0.0"))
        }
    }

    @Test fun `cancelling delivery closes the in flight socket`() = runBlocking {
        ServerSocket(0).use { server ->
            val entered = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Boolean>()
            Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    entered.complete(Unit)
                    closed.complete(runCatching { input.read() == -1 }.getOrDefault(false))
                }
            }.apply { isDaemon = true; start() }
            val job = launch { AttributionHttpClient("http://127.0.0.1:${server.localPort}").report(payload) }
            withTimeout(3000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(withTimeout(3000) { closed.await() })
        }
    }

    private fun serve(server: ServerSocket, response: String): CompletableDeferred<String> {
        val captured = CompletableDeferred<String>()
        Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                val lines = mutableListOf<String>()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    lines += line
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { input.read() }
                captured.complete(lines.joinToString("\n"))
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n" + response).toByteArray())
            }
        }.apply { isDaemon = true; start() }
        return captured
    }
}
