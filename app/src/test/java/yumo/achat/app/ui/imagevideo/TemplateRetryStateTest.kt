package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.backend.TemplateLoadResult
import yumo.achat.core.backend.VisualTemplate

class TemplateRetryStateTest {
    @Test
    fun `failed video retry preserves both successful modality lists`() {
        val oldVideo = template("video-old")
        val oldImage = template("image-old")
        val state = AchatBackendUiState(
            videoTemplates = listOf(oldVideo),
            imageTemplates = listOf(oldImage),
            videoTemplatesLoading = true,
        )

        val updated = state.withTemplateLoadResult(
            modality = "video",
            result = TemplateLoadResult(emptyList(), "HTTP 503"),
        )

        assertEquals(listOf(oldVideo), updated.videoTemplates)
        assertEquals(listOf(oldImage), updated.imageTemplates)
        assertEquals("HTTP 503", updated.videoTemplateErrorMessage)
        assertFalse(updated.videoTemplatesLoading)
    }

    @Test
    fun `successful image retry only replaces image list and clears its error`() {
        val oldVideo = template("video-old")
        val newImage = template("image-new")
        val state = AchatBackendUiState(
            videoTemplates = listOf(oldVideo),
            imageTemplateErrorMessage = "old error",
            imageTemplatesLoading = true,
        )

        val updated = state.withTemplateLoadResult(
            modality = "image",
            result = TemplateLoadResult(listOf(newImage), null),
        )

        assertEquals(listOf(oldVideo), updated.videoTemplates)
        assertEquals(listOf(newImage), updated.imageTemplates)
        assertNull(updated.imageTemplateErrorMessage)
        assertFalse(updated.imageTemplatesLoading)
    }

    private fun template(id: String) = VisualTemplate(
        id = id,
        categoryId = null,
        categoryName = null,
        name = id,
        fileUrl = "https://example.test/$id",
        mimeType = "image/webp",
        width = 1,
        height = 1,
        durationSeconds = 0,
        hotScore = 0,
        fastPrice = 1,
        qualityPrice = 2,
    )
}
