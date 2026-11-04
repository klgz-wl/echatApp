package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.IOException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStartupControllerTest {
    private fun timeoutController() = HomeStartupController(
        awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = {}, awaitHomeDataGate = {},
        networkTimeoutMillis = 20,
    )

    @Test fun `owned auth timeout is a visible failure before session exists`() = runBlocking {
        var profileCalls = 0
        val failure = runCatching {
            timeoutController().startup(authenticate = { awaitCancellation() }, profile = { profileCalls++ })
        }.exceptionOrNull()
        assertTrue(failure is StartupOperationTimeout)
        assertEquals(0, profileCalls)
    }

    @Test fun `owned first profile timeout preserves authenticated session for splash retry`() = runBlocking {
        var saved: String? = null
        val controller = timeoutController()
        val failure = runCatching {
            controller.startup(authenticate = { saved = "saved"; saved }, profile = { awaitCancellation() })
        }.exceptionOrNull()
        assertTrue(failure is StartupOperationTimeout)
        assertEquals("saved", saved)
        assertEquals("saved" to "profile", controller.resumeAfterAuthentication(saved) { "profile" })
    }

    @Test fun `external cancellation remains cancellation across all owned timeout boundaries`() = runBlocking {
        val cancelled = CancellationException("screen disposed")
        val failure = runCatching {
            timeoutController().restoreSession("saved") { throw cancelled }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.javaClass, failure?.javaClass)
        assertEquals(cancelled.message, failure?.message)
        assertTrue(currentCoroutineContext().isActive)
        val outerTimeout = runCatching {
            withTimeout(5) { timeoutController().restoreSession("saved") { awaitCancellation() } }
        }.exceptionOrNull()
        assertTrue(outerTimeout is CancellationException)
        assertTrue(outerTimeout !is StartupOperationTimeout)
    }

    @Test fun `timeout inside owned request is visible without cancelling the caller`() = runBlocking {
        val failure = runCatching {
            timeoutController().restoreSession("saved") {
                withTimeout(5) { awaitCancellation() }
            }
        }.exceptionOrNull()
        assertTrue("Expected owned timeout, got $failure", failure is StartupOperationTimeout)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `outer timeout cancels profile refresh instead of exhausting retries`() = runBlocking {
        var attempts = 0
        val failure = runCatching {
            withTimeout(5) {
                timeoutController().refreshAttributionProfile(10, 1, { true }) {
                    attempts++
                    awaitCancellation()
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, attempts)
    }

    @Test fun `successful nullable operation is not treated as timeout`() = runBlocking {
        val result = timeoutController().restoreSession("saved") { null }
        assertEquals("saved" to null, result)
    }

    @Test fun `valid submission prepares identity before binding and beginning attribution`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" }, awaitPostAuth = { calls += "post" },
            awaitHomeWindow = {}, awaitHomeDataGate = {}, networkTimeoutMillis = 1_000,
            beginAuthentication = { calls += "begin" },
        )
        controller.startup(
            prepare = { calls += "identity"; calls += "bind" },
            authenticate = { calls += "auth"; "session" }, profile = { calls += "profile"; "profile" },
        )
        assertEquals(listOf("identity", "bind", "begin", "pre", "auth", "post", "profile"), calls)
    }

    @Test fun `identity preparation failure cannot start attribution or authentication`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" }, awaitPostAuth = {}, awaitHomeWindow = {},
            awaitHomeDataGate = {}, networkTimeoutMillis = 1_000, beginAuthentication = { calls += "begin" },
        )
        assertThrows(IllegalStateException::class.java) { runBlocking {
            controller.startup(prepare = { error("identity unavailable") },
                authenticate = { calls += "auth" }, profile = { calls += "profile" })
        } }
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `session restoration never begins authentication or waits attribution`() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = HomeStartupController(
            awaitPreAuth = { calls += "pre" }, awaitPostAuth = { calls += "post" },
            awaitHomeWindow = {}, awaitHomeDataGate = {}, networkTimeoutMillis = 1_000,
            beginAuthentication = { calls += "begin" },
        )
        val result = controller.restoreSession("saved") { calls += "profile"; "profile" }
        assertEquals("saved" to "profile", result)
        assertEquals(listOf("profile"), calls)
    }

    @Test fun `exhausted attribution profile refresh is retryable without authentication or report calls`() = runBlocking {
        var attempts = 0
        var succeeds = false
        val controller = HomeStartupController(
            awaitPreAuth = { error("must not authenticate") }, awaitPostAuth = { error("must not authenticate") },
            awaitHomeWindow = {}, awaitHomeDataGate = {}, networkTimeoutMillis = 1_000,
        )
        assertThrows(AttributionProfileRefreshExhausted::class.java) { runBlocking {
            controller.refreshAttributionProfile(2, 1, { true }) {
                attempts++; throw IOException("offline")
            }
        } }
        succeeds = true
        val result = controller.refreshAttributionProfile(2, 1, { true }) {
            attempts++; check(succeeds); "profile"
        }
        assertEquals("profile", result)
        assertEquals(3, attempts)
    }

    @Test fun `profile response from superseded result or account is discarded`() = runBlocking {
        var current = true
        val controller = HomeStartupController(
            awaitPreAuth = {}, awaitPostAuth = {}, awaitHomeWindow = {}, awaitHomeDataGate = {},
            networkTimeoutMillis = 1_000,
        )
        val result = controller.refreshAttributionProfile(3, 1, { current }) {
            current = false
            "stale"
        }
        assertEquals(null, result)
    }
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
