package yumo.achat.app.ui.imagevideo

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows
import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.AchatBackendHttpException
import yumo.achat.core.backend.GenerationCreationRejectedException
import yumo.achat.core.backend.GenerationNotSubmittedException
import yumo.achat.core.backend.ImagePreparationException
import yumo.achat.core.backend.SignedOutException

class GenerationDiagnosticTest {
    @Test
    fun `normal cancellation never becomes a user diagnostic`() {
        assertThrows(CancellationException::class.java) { imageUploadDiagnostic(CancellationException()) }
        assertThrows(CancellationException::class.java) { generationSubmissionDiagnostic(CancellationException()) }
    }
    @Test
    fun `preparation upload and uncertain creation retain distinct stages`() {
        assertEquals("E-301", imageUploadDiagnostic(ImagePreparationException(IOException("read failed"))))
        assertEquals("E-408", imageUploadDiagnostic(IOException("upload timeout")))
        assertEquals("E-402", generationSubmissionDiagnostic(IOException("lost response")))
        assertEquals("E-409", generationSubmissionDiagnostic(GenerationNotSubmittedException("input missing")))
        assertEquals("E-409", generationSubmissionDiagnostic(GenerationCreationRejectedException(400, "Invalid input")))
    }

    @Test
    fun `specific business evidence wins over generic rejection`() {
        assertEquals("E-207", generationSubmissionDiagnostic(SignedOutException()))
        assertEquals("E-405", generationSubmissionDiagnostic(IllegalStateException("Insufficient diamond balance")))
        assertEquals("E-406", generationSubmissionDiagnostic(GenerationCreationRejectedException(409, "Conflict")))
        assertEquals("E-407", generationSubmissionDiagnostic(GenerationCreationRejectedException(503, "Unavailable")))
        val http = AchatBackendHttpException(409, "business envelope")
        http.initCause(GenerationCreationRejectedException(409, "Conflict"))
        assertEquals("E-406", generationSubmissionDiagnostic(http))
    }

    @Test
    fun `http status alone does not prove session conflict or service availability`() {
        listOf(401, 403, 409, 503).forEach { status ->
            assertEquals("E-402", generationSubmissionDiagnostic(AchatBackendHttpException(status, "")))
        }
        assertEquals("E-402", generationSubmissionDiagnostic(IllegalStateException("Missing response data")))
    }
}
