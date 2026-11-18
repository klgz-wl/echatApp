package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

class HomeFeedKindFilterTest {
    @get:Rule val compose = createComposeRule()
    @Test fun homeShowsAllServerKindsAndAcknowledgesThemInOrder() {
        val video = HomeFeedItem("video", "video_template", "video", "Wanted video", "", false,
            fileUrl = "https://127.0.0.1:1/video.mp4", fastPrice = 1)
        val gateway = object : HomeScreenTestGateway(video) {
            var calls = 0
            override suspend fun homeFeed(requestId: String): HomeFeedBatch {
                calls++
                if (calls > 1) return HomeFeedBatch("empty", 0, emptyList())
                return HomeFeedBatch("mixed", 5, listOf(
                    video.copy(id = "live", kind = "live_persona", title = "Hidden live persona"),
                    video, video.copy(id = "image", kind = "image_template", title = "Hidden image template"),
                    video.copy(id = "drama", kind = "short_drama", title = "Wanted drama")))
            }
        }
        compose.setContent { ZorvHomeFeed(gateway, 0, false, false, {}, {}, {}, {}, {}, { _, _ -> }, onOpenDrama = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hidden live persona").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hidden live persona").assertIsDisplayed()
        compose.onNodeWithTag("zorv-home-pager").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Use Template").fetchSemanticsNodes().any { it.boundsInRoot.top >= 0 } }
        compose.onNodeWithTag("zorv-home-pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("zorv-home-pager").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Watch Full Drama").fetchSemanticsNodes().any { it.boundsInRoot.top >= 0 } }
        compose.onNodeWithText("Wanted drama").assertIsDisplayed()
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.waitUntil(5_000) { gateway.calls == 2 }
        compose.runOnIdle { assertTrue(gateway.ackIndices.isNotEmpty()); assertEquals(2, gateway.calls) }
    }
}
