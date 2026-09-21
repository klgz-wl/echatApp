package yumo.achat.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows

class TemplateLoadResultTest {
    @Test
    fun `successful empty response remains a genuine empty state`() {
        val result = loadTemplateResult("Unable to load video templates") { emptyList() }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertNull(result.errorMessage)
    }

    @Test
    fun `failed response retains its error instead of becoming successful empty`() {
        val result = loadTemplateResult("Unable to load video templates") {
            throw IllegalStateException("HTTP 503")
        }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertEquals("HTTP 503", result.errorMessage)
    }

    @Test
    fun `session failure is represented as retryable template failure`() {
        val result = loadTemplateResult(
            fallbackMessage = "Unable to load video templates",
            load = { throw IllegalStateException("Session unavailable") },
        )

        assertEquals("Session unavailable", result.errorMessage)
    }

    @Test
    fun `template loading does not swallow coroutine cancellation`() {
        assertThrows(CancellationException::class.java) {
            loadTemplateResult("fallback") { throw CancellationException("cancel") }
        }
    }
}
