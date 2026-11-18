package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

internal open class HomeScreenTestGateway(private val item: HomeFeedItem, private val initialFailure: Boolean = false) : HomeFeedGateway {
    var requests = 0
    var ackIndices = mutableListOf<Int>()
    var previewRequests = 0
    override suspend fun homeFeed(requestId: String): HomeFeedBatch {
        requests++
        if (requests == 1 && initialFailure) error("offline")
        return if (ackIndices.isEmpty()) HomeFeedBatch("batch", 5, listOf(item)) else HomeFeedBatch("empty", 0, emptyList())
    }
    override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) { ackIndices += displayedThroughIndex }
    override suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview { previewRequests++; error("No preview configured") }
    override suspend fun shortDramaDetail(dramaId: String) = ShortDramaDetail(dramaId, "Real drama title", "", listOf("Mystery"),
        listOf(ShortDramaEpisode("ep-1", 1, "First episode", 0, false, true, "allowed")), 1)
}

class ZorvHomeFeedScreenTest {
    @get:Rule val compose = createComposeRule()
    private val item = HomeFeedItem("catalog-1", "video_template", "home-1", "Featured portrait", "", true,
        fileUrl = "https://127.0.0.1:1/video.mp4", durationSeconds = 9, fastPrice = 14, qualityPrice = 20)
    @Test fun loadingErrorAndReadyKeepIdenticalOuterCardBounds() {
        val pending = CompletableDeferred<Unit>()
        var attempts = 0
        val gateway = object : HomeScreenTestGateway(item) {
            override suspend fun homeFeed(requestId: String): HomeFeedBatch {
                if (attempts++ == 0) { pending.await(); error("offline") }
                return super.homeFeed(requestId)
            }
        }
        compose.setContent { ZorvHomeFeed(gateway, 0, false, false, {}, {}, {}, {}, {}, { _, _ -> }, onOpenDrama = {}) }
        compose.onNodeWithText("Loading Home content…").assertIsDisplayed()
        val loadingBounds = compose.onNodeWithTag("home-feed-card-frame").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { pending.complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unable to load Home content", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(loadingBounds, compose.onNodeWithTag("home-feed-card-frame").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("RETRY").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use Template").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(loadingBounds, compose.onNodeWithTag("home-feed-card-frame").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun homeUsesBrandAndInCardActionWithoutCatalogControls() {
        var selected: SelectedGenerationTemplate? = null
        val gateway = HomeScreenTestGateway(item)
        compose.setContent {
            ZorvHomeFeed(gateway, 88, false, false, {}, {}, {}, {}, { selected = it }, { _, _ -> }, onOpenDrama = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use Template").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Zorv").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        compose.onAllNodesWithText("Hot").assertCountEquals(0)
        compose.onAllNodesWithText("New").assertCountEquals(0)
        compose.onNodeWithText("HOME").assertIsSelected()
        compose.onNodeWithText("Use Template").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("home-1", selected?.templateId); assertEquals("home", selected?.source) }
    }
    @Test fun feedLoadErrorRetriesAndShowsReturnedTemplates() {
        val gateway = HomeScreenTestGateway(item, initialFailure = true)
        compose.setContent { ZorvHomeFeed(gateway, 0, false, false, {}, {}, {}, {}, {}, { _, _ -> }, onOpenDrama = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unable to load Home content", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("RETRY").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use Template").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Use Template").assertIsDisplayed()
    }
}
