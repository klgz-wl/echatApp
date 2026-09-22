package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.backend.VisualTemplate

class TemplateVideoPreloadTargetsTest {
    @Test
    fun `preloads next and previous without competing for current video`() {
        val templates = listOf(
            template("previous", "https://example.test/previous.mp4", "video/mp4"),
            template("current", "https://example.test/current.mp4", "video/mp4"),
            template("next", "https://example.test/next.mp4", "video/mp4"),
        )

        val urls = templates.nearbyVideoPreviewUrls(selectedIndex = 1)

        assertEquals(
            listOf(
                "https://example.test/next.mp4",
                "https://example.test/previous.mp4",
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

        assertEquals(
            listOf("https://example.test/next.mp4"),
            urls,
        )
    }

    @Test
    fun `prefetches next and previous images without duplicating visible request`() {
        val templates = listOf(
            template("previous", "https://example.test/previous.webp", "image/webp"),
            template("current", "https://example.test/current.webp", "image/webp"),
            template("next", "https://example.test/next.webp", "image/webp"),
        )

        assertEquals(
            listOf(
                "https://example.test/next.webp",
                "https://example.test/previous.webp",
            ),
            templates.nearbyImagePreviewUrls(selectedIndex = 1),
        )
    }

    @Test
    fun `does not duplicate current video poster request`() {
        val templates = listOf(
            template(
                id = "video",
                fileUrl = "https://example.test/video.mp4",
                mimeType = "video/mp4",
                previewUrl = "https://example.test/video-poster.webp",
            ),
        )

        assertEquals(emptyList<String>(), templates.nearbyImagePreviewUrls(selectedIndex = 0))
    }
}

private fun template(
    id: String,
    fileUrl: String,
    mimeType: String,
    previewUrl: String = "",
): VisualTemplate =
    VisualTemplate(
        id = id,
        categoryId = null,
        categoryName = null,
        name = id,
        fileUrl = fileUrl,
        previewUrl = previewUrl,
        mimeType = mimeType,
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 0,
        fastPrice = 1,
        qualityPrice = 2,
    )
