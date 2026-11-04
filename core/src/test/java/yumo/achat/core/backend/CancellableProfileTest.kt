package yumo.achat.core.backend

import java.net.ServerSocket
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableProfileTest {
    @Test fun `cancelling profile work closes the actual HTTP socket`() = verifyCancellation {
        it.userProfileCancellable("test-token")
    }

    @Test fun `cancelling a 401 refresh closes its actual HTTP socket`() = verifyCancellation {
        it.refreshAccessTokenCancellable("test-refresh")
    }

    private fun verifyCancellation(operation: suspend (AchatBackendClient) -> Any) = runBlocking {
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
            val client = AchatBackendClient("http://127.0.0.1:${server.localPort}")
            val job = launch { operation(client) }
            withTimeout(3000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(withTimeout(3000) { closed.await() })
        }
    }
}
