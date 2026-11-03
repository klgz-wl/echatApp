package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking
import yumo.achat.core.network.ServiceFailure

class TemplateLoadResultTest {
    @Test
    fun `successful empty response remains a genuine empty state`() = runBlocking {
        val result = loadTemplateResult("Unable to load video templates") {
            PagedResult<VisualTemplate>(emptyList(), page = 1, pageSize = 20, total = 0L)
        }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertNull(result.errorMessage)
        assertFalse(result.sessionUnavailable)
    }

    @Test
    fun `failed response retains its error instead of becoming successful empty`() = runBlocking {
        val result = loadTemplateResult("Unable to load video templates") {
            throw IllegalStateException("HTTP 503")
        }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertEquals("HTTP 503", result.errorMessage)
        assertFalse(result.sessionUnavailable)
    }

    @Test
    fun `session text alone is not session unavailable evidence`() = runBlocking {
        val result = loadTemplateResult(
            fallbackMessage = "Unable to load video templates",
            load = { throw IllegalStateException("Session unavailable") },
        )

        assertEquals("Session unavailable", result.errorMessage)
        assertFalse(result.sessionUnavailable)
    }

    @Test
    fun `typed session failures preserve evidence and existing error message fallback`() = runBlocking {
        val fallback = "Unable to load video templates"
        for (failure in listOf(SignedOutException(), ServiceFailure.SignedOut, ServiceFailure.Superseded)) {
            val result = loadTemplateResult(fallback) { throw failure }

            assertTrue(result.sessionUnavailable)
            assertEquals(emptyList<VisualTemplate>(), result.templates)
            assertEquals(failure.message?.takeIf { it.isNotBlank() } ?: fallback, result.errorMessage)
        }
    }

    @Test
    fun `HTTP unauthorized and forbidden alone do not establish session unavailability`() = runBlocking {
        for (status in listOf(401, 403)) {
            val failure = AchatBackendHttpException(status, "Permission denied")
            val result = loadTemplateResult("Unable to load video templates") { throw failure }

            assertFalse(result.sessionUnavailable)
            assertEquals("Permission denied", result.errorMessage)
        }
    }

    @Test
    fun `template loading does not swallow coroutine cancellation`() {
        val cancellation = CancellationException("cancel")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { loadTemplateResult("fallback") { throw cancellation } }
        }
        assertSame(cancellation, error)
    }
}
