package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.data.backend.VisualTemplate

class TemplateVideoPreloadTargetsTest {
    @Test
    fun `uses next and previous video urls near selected template`() {
        val templates = listOf(
            template("current", "https://example.test/current.mp4", "video/mp4"),
            template("next", "https://example.test/next.mp4", "video/mp4"),
            template("image", "https://example.test/image.webp", "image/webp"),
            template("previous", "https://example.test/previous.mp4", "video/mp4"),
        )

        val urls = templates.nearbyVideoPreviewUrls(selectedIndex = 2)

        assertEquals(
            listOf(
                "https://example.test/previous.mp4",
                "https://example.test/next.mp4",
            ),
            urls,
        )
    }

    @Test
    fun `does not wrap preload targets at feed edges`() {
        val templates = listOf(
            template("current", "https://example.test/current.mp4", "video/mp4"),
            template("next", "https://example.test/next.mp4", "video/mp4"),
            template("last", "https://example.test/last.mp4", "video/mp4"),
        )

        val urls = templates.nearbyVideoPreviewUrls(selectedIndex = 0)

        assertEquals(listOf("https://example.test/next.mp4"), urls)
    }
}

private fun template(id: String, fileUrl: String, mimeType: String): VisualTemplate =
    VisualTemplate(
        id = id,
        categoryId = null,
        categoryName = null,
        name = id,
        fileUrl = fileUrl,
        mimeType = mimeType,
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 0,
        fastPrice = 1,
        qualityPrice = 2,
    )
