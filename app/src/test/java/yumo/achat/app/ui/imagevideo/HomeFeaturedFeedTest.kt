package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import yumo.achat.core.backend.TemplateLoadResult
import yumo.achat.core.backend.VisualTemplate

class HomeFeaturedFeedTest {
    @Test fun `temporary drama preview failures retry with one second backoff`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val gateway = object : yumo.achat.core.backend.HomeFeedGateway {
            override suspend fun resolveDramaPreview(resolverUrl: String): yumo.achat.core.backend.ResolvedDramaPreview {
                attempts++
                if (attempts < 3) throw yumo.achat.core.backend.HomeFeedServiceException("MEDIA_TEMPORARILY_UNAVAILABLE", 503)
                return yumo.achat.core.backend.ResolvedDramaPreview("https://cdn.test/preview.mp4", "2030-01-01T00:00:00Z")
            }
            override suspend fun homeFeed(requestId: String) = error("unused")
            override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) = Unit
            override suspend fun shortDramaDetail(dramaId: String) = error("unused")
        }
        val media = resolveDramaPreviewWithRetry(gateway, "/preview", waitBeforeRetry = { waits += it })
        assertEquals("https://cdn.test/preview.mp4", media.url)
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 1_000L), waits)
    }
    @Test fun `home selection preserves generation contract and attribution source`() {
        val selected = template("a").homeGenerationTemplate()!!
        assertEquals("home", selected.source)
        assertEquals("video", selected.modality)
        assertEquals("fast", selected.quality)
        assertEquals(14, selected.quotedDiamondCost)
        assertTrue(selected.vip)
    }
    @Test fun `mixed templates keep resource ID modality prices and original catalog context`() {
        val context = yumo.achat.core.backend.HomeCatalogContext("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 7)
        val item = yumo.achat.core.backend.HomeFeedItem("catalog-entry", "image_template", "actual-template", "Picture", "cover", true,
            fileUrl = "https://cdn.test/image.png", fastPrice = 8, catalogContext = context)
        val selected = item.generationTemplate()!!
        assertEquals("actual-template", selected.templateId)
        assertEquals("image", selected.modality)
        assertEquals(8, selected.quotedDiamondCost)
        assertEquals(context, selected.catalogContext)
        assertNull(item.copy(kind = "short_drama").generationTemplate())
        assertNull(item.copy(fastPrice = null, qualityPrice = null).generationTemplate())
    }
    private fun template(id: String) = VisualTemplate(id, "category", "Category", "Template", "https://cdn.test/video.mp4",
        "https://cdn.test/poster.jpg", "video/mp4", 720, 1280, 9, 100, 14, 20, vip = true)
}
