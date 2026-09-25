package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.backend.TemplateLoadResult
import yumo.achat.core.backend.VisualTemplate

class TemplateRetryStateTest {
    @Test
    fun `template tabs map to backend sort values`() {
        assertEquals("latest", templateSortBy(TemplateSection.Video, selectedTab = 0))
        assertEquals("latest", templateSortBy(TemplateSection.Video, selectedTab = 1))
        assertEquals("latest", templateSortBy(TemplateSection.Image, selectedTab = 0))
        assertEquals("latest", templateSortBy(TemplateSection.Image, selectedTab = 1))
    }

    @Test
    fun `video removes sort tabs while image keeps its mode tabs`() {
        assertFalse(shouldShowTemplateTabs(TemplateSection.Video))
        assertTrue(shouldShowTemplateTabs(TemplateSection.Image))
    }

    @Test
    fun `loaded template does not show its name above media`() {
        assertFalse(
            shouldShowTemplateBackendStatus(
                isLoading = false,
                errorMessage = null,
                hasSelectedTemplate = true,
            ),
        )
        assertTrue(
            shouldShowTemplateBackendStatus(
                isLoading = true,
                errorMessage = null,
                hasSelectedTemplate = false,
            ),
        )
    }

    @Test
    fun `template retry escalates to startup retry when initial home load failed`() {
        val state = AchatBackendUiState(
            isLoading = false,
            errorMessage = "Backend unavailable",
            videoTemplates = emptyList(),
            videoTemplatesLoading = false,
            videoTemplateErrorMessage = "Unable to load video templates",
        )

        assertEquals(TemplateRetryAction.Startup, templateRetryAction(state, TemplateSection.Video))
    }

    @Test
    fun `template retry stays scoped after home load succeeded`() {
        val state = AchatBackendUiState(
            isLoading = false,
            errorMessage = null,
            videoTemplates = emptyList(),
            videoTemplatesLoading = false,
            videoTemplateErrorMessage = "Unable to load video templates",
        )

        assertEquals(TemplateRetryAction.Section, templateRetryAction(state, TemplateSection.Video))
    }

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

    @Test
    fun `offline template failure uses local visual fallback without synthetic price`() {
        assertTrue(
            shouldShowLocalTemplateFallback(
                templates = emptyList(),
                isLoading = false,
                errorMessage = "Unable to resolve host",
            ),
        )
        assertNull(visibleTemplatePrice(null))
    }

    @Test
    fun `template price is shown only for selected live template quote`() {
        assertEquals(1, visibleTemplatePrice(template("video-live")))
    }

    @Test
    fun `live template placeholder animates only while loading`() {
        assertTrue(shouldAnimateLiveTemplatePlaceholder(isLoading = true))
        assertFalse(shouldAnimateLiveTemplatePlaceholder(isLoading = false))
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
