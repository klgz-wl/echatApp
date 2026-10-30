package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.IOException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeStartupControllerTest {
    @Test fun `retry after successful authentication resumes at post auth without signing in again`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" },
            awaitPostAuth = { calls += "post" },
            awaitHomeWindow = {}, awaitHomeDataGate = {}, networkTimeoutMillis = 1_000,
        )

        val result = controller.resumeAfterAuthentication("saved-session") {
            calls += "profile"
            "loaded-profile"
        }

        assertEquals("saved-session" to "loaded-profile", result)
        assertEquals(listOf("post", "profile"), calls)
    }

    @Test fun `completed attribution bypasses every splash and home attribution hook`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            attributionCompleted = { true },
            awaitPreAuth = { calls += "pre" },
            awaitPostAuth = { calls += "post" },
            awaitHomeWindow = { calls += "home-window" },
            awaitHomeDataGate = { calls += "home-gate" },
            networkTimeoutMillis = 1_000,
        )

        val startup = controller.startup(
            authenticate = { calls += "auth"; "session" },
            profile = { calls += "profile"; "profile" },
        )
        val home = controller.loadHome { calls += "home"; "loaded" }
        val page = controller.loadTemplates { calls += "page"; "page-loaded" }

        assertEquals("session" to "profile", startup)
        assertEquals("loaded", home)
        assertEquals("page-loaded", page)
        assertEquals(listOf("auth", "profile", "home", "page"), calls)
    }

    @Test fun `identity change after authentication re-enables post auth attribution`() = runBlocking {
        var completed = true
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            attributionCompleted = { completed },
            awaitPreAuth = { calls += "pre" },
            awaitPostAuth = { calls += "post" },
            awaitHomeWindow = {},
            awaitHomeDataGate = {},
            networkTimeoutMillis = 1_000,
        )

        controller.startup(
            authenticate = { calls += "auth"; completed = false; "new-session" },
            profile = { calls += "profile"; "profile" },
        )

        assertEquals(listOf("auth", "post", "profile"), calls)
    }

    @Test fun `completed attribution stays bypassed across concurrent startup and page entry`() = runBlocking {
        var attributionCalls = 0
        val controller = HomeStartupController(
            attributionCompleted = { true },
            awaitPreAuth = { attributionCalls++ },
            awaitPostAuth = { attributionCalls++ },
            awaitHomeWindow = { attributionCalls++ },
            awaitHomeDataGate = { attributionCalls++ },
            networkTimeoutMillis = 1_000,
        )

        listOf(
            async { controller.startup(authenticate = { "session-1" }, profile = { "profile-1" }) },
            async { controller.startup(authenticate = { "session-2" }, profile = { "profile-2" }) },
            async { controller.loadHome { "home" } },
            async { controller.loadTemplates { "page" } },
        ).awaitAll()

        assertEquals(0, attributionCalls)
    }
    @Test fun `splash waits before auth and after auth before first profile`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" },
            awaitPostAuth = { calls += "post" },
            awaitHomeWindow = { calls += "home-window" },
            awaitHomeDataGate = { calls += "home-gate" },
            networkTimeoutMillis = 1_000,
        )

        val startup = controller.startup(
            authenticate = { calls += "auth"; "session" },
            profile = { calls += "profile"; "profile" },
        )

        assertEquals("session" to "profile", startup)
        assertEquals(listOf("pre", "auth", "post", "profile"), calls)
    }

    @Test fun `home data waits for home window and matching report gate`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {},
            awaitHomeWindow = { calls += "home-window" },
            awaitHomeDataGate = { calls += "home-gate" },
            networkTimeoutMillis = 1_000,
        )

        controller.loadHome { calls += "templates-and-categories" }
        controller.loadTemplates { calls += "page-2" }

        assertEquals(listOf("home-window", "home-gate", "templates-and-categories", "home-window", "home-gate", "page-2"), calls)
    }

    @Test fun `authentication failure leaves first profile and home requests untouched`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" }, awaitPostAuth = { calls += "post" },
            awaitHomeWindow = {}, awaitHomeDataGate = {}, networkTimeoutMillis = 1_000,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                controller.startup(
                    authenticate = { calls += "auth"; error("failed") },
                    profile = { calls += "profile" },
                )
            }
        }
        assertEquals(listOf("pre", "auth"), calls)
    }

    @Test fun `report gate failure keeps home data and pagination offline`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = { calls += "window" },
            awaitHomeDataGate = { calls += "gate"; error("report failed") },
            networkTimeoutMillis = 1_000,
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { controller.loadHome { calls += "home" } } }
        assertThrows(IllegalStateException::class.java) { runBlocking { controller.loadTemplates { calls += "page" } } }
        assertEquals(listOf("window", "gate", "window", "gate"), calls)
    }

    @Test fun `transient TLS login and profile IO failures recover within their stage budgets`() = runBlocking {
        var authAttempts = 0
        var profileAttempts = 0
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = {}, awaitHomeDataGate = {},
            networkTimeoutMillis = 3_000,
        )

        val result = controller.startup(
            authenticate = {
                authAttempts++
                if (authAttempts < 3) throw SSLException("Unable to parse TLS packet header")
                "session"
            },
            profile = {
                profileAttempts++
                if (profileAttempts == 1) throw IOException("unexpected end of stream")
                "profile"
            },
        )

        assertEquals("session" to "profile", result)
        assertEquals(3, authAttempts)
        assertEquals(2, profileAttempts)
    }

    @Test fun `persistent network failure stops after three attempts`() = runBlocking {
        var attempts = 0
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = {}, awaitHomeDataGate = {},
            networkTimeoutMillis = 3_000,
        )

        assertThrows(IOException::class.java) {
            runBlocking {
                controller.startup(
                    authenticate = { attempts++; throw IOException("offline") },
                    profile = { "profile" },
                )
            }
        }
        assertEquals(3, attempts)
    }

    @Test fun `profile retry waits past cached refresh failure`() = runBlocking {
        var firstFailureAtNanos = 0L
        var attempts = 0
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = {}, awaitHomeDataGate = {},
            networkTimeoutMillis = 5_000,
        )

        val result = controller.startup(
            authenticate = { "session" },
            profile = {
                attempts++
                if (firstFailureAtNanos == 0L) firstFailureAtNanos = System.nanoTime()
                if ((System.nanoTime() - firstFailureAtNanos) < 1_000_000_000L) {
                    throw IOException("cached refresh failure")
                }
                "profile"
            },
        )

        assertEquals("session" to "profile", result)
        assertEquals(2, attempts)
    }
}
