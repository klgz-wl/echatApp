package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import kotlinx.coroutines.runBlocking

class TemplateLoadResultTest {
    @Test
    fun `successful empty response remains a genuine empty state`() = runBlocking {
        val result = loadTemplateResult("Unable to load video templates") {
            PagedResult<VisualTemplate>(emptyList(), page = 1, pageSize = 20, total = 0L)
        }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertNull(result.errorMessage)
    }

    @Test
    fun `failed response retains its error instead of becoming successful empty`() = runBlocking {
        val result = loadTemplateResult("Unable to load video templates") {
            throw IllegalStateException("HTTP 503")
        }

        assertEquals(emptyList<VisualTemplate>(), result.templates)
        assertEquals("HTTP 503", result.errorMessage)
    }

    @Test
    fun `session failure is represented as retryable template failure`() = runBlocking {
        val result = loadTemplateResult(
            fallbackMessage = "Unable to load video templates",
            load = { throw IllegalStateException("Session unavailable") },
        )

        assertEquals("Session unavailable", result.errorMessage)
    }

    @Test
    fun `template loading does not swallow coroutine cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { loadTemplateResult("fallback") { throw CancellationException("cancel") } }
        }
    }
}
