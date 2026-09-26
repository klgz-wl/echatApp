package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.backend.VisualTemplate
import androidx.compose.ui.layout.ContentScale
import coil.size.Scale

class TemplatePlaybackStateTest {
    @Test
    fun `only current pager item is allowed to play`() {
        assertTrue(shouldPlayTemplatePage(page = 2, currentPage = 2, requestedPlaying = true))
        assertFalse(shouldPlayTemplatePage(page = 1, currentPage = 2, requestedPlaying = true))
        assertFalse(shouldPlayTemplatePage(page = 2, currentPage = 2, requestedPlaying = false))
    }

    @Test
    fun `pager key uses stable template id`() {
        val templates = listOf(template("template-a"), template("template-b"))
        assertEquals("template-b", templatePagerKey(templates, 1))
    }

    @Test
    fun `video poster stays visible until first frame`() {
        assertTrue(shouldShowVideoPoster(hasRenderedFirstFrame = false))
        assertFalse(shouldShowVideoPoster(hasRenderedFirstFrame = true))
        assertFalse(shouldShowVideoPoster(hasRenderedFirstFrame = false, hasPlaybackError = true))
        assertTrue(shouldEnableHeroPlaybackToggle(hasMediaError = false))
        assertFalse(shouldEnableHeroPlaybackToggle(hasMediaError = true))
    }

    @Test
    fun `remote media loading indicator is visible only before success or error`() {
        assertTrue(shouldShowTemplateMediaLoading(hasRenderedRemoteContent = false, hasMediaError = false))
        assertFalse(shouldShowTemplateMediaLoading(hasRenderedRemoteContent = true, hasMediaError = false))
        assertFalse(shouldShowTemplateMediaLoading(hasRenderedRemoteContent = false, hasMediaError = true))
        assertTrue(shouldShowVideoRetry(hasPlaybackError = true))
        assertFalse(shouldShowVideoRetry(hasPlaybackError = false))
    }

    @Test
    fun `video pauses outside started lifecycle`() {
        assertTrue(shouldPlayVideo(requestedPlaying = true, lifecycleStarted = true))
        assertFalse(shouldPlayVideo(requestedPlaying = true, lifecycleStarted = false))
    }

    @Test
    fun `coil decode scale follows display content scale`() {
        assertEquals(Scale.FILL, coilScaleForContentScale(ContentScale.Crop))
        assertEquals(Scale.FIT, coilScaleForContentScale(ContentScale.Fit))
    }

    private fun template(id: String) = VisualTemplate(
        id = id,
        categoryId = null,
        categoryName = null,
        name = id,
        fileUrl = "https://example.test/$id.mp4",
        mimeType = "video/mp4",
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 0,
        fastPrice = 1,
        qualityPrice = 2,
    )
}
