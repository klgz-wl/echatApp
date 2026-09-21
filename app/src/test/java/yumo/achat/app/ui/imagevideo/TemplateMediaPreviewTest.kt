package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.data.backend.VisualTemplate

class TemplateMediaPreviewTest {
    @Test
    fun `uses remote preview for image templates with urls`() {
        val template = template(mimeType = "image/webp", fileUrl = "https://example.test/template.webp")

        val media = template.toPreviewMedia()

        assertEquals(TemplatePreviewMedia.RemoteImage("https://example.test/template.webp"), media)
    }

    @Test
    fun `uses remote video preview for video templates with urls`() {
        val template = template(
            mimeType = "video/mp4",
            fileUrl = "https://example.test/template.mp4",
            previewUrl = "https://example.test/poster.webp",
        )

        val media = template.toPreviewMedia()

        assertEquals(
            TemplatePreviewMedia.RemoteVideo(
                url = "https://example.test/template.mp4",
                posterUrl = "https://example.test/poster.webp",
            ),
            media,
        )
    }

    @Test
    fun `uses local placeholder when image url is blank`() {
        val template = template(mimeType = "image/png", fileUrl = "")

        val media = template.toPreviewMedia()

        assertEquals(TemplatePreviewMedia.LocalPlaceholder, media)
    }

    private fun template(mimeType: String, fileUrl: String, previewUrl: String = "") = VisualTemplate(
        id = "template",
        categoryId = null,
        categoryName = null,
        name = "Template",
        fileUrl = fileUrl,
        previewUrl = previewUrl,
        mimeType = mimeType,
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 1,
        fastPrice = 11,
        qualityPrice = null,
    )
}
