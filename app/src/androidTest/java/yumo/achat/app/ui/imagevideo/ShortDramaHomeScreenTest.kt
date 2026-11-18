package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*
import java.time.Instant

class ShortDramaHomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val drama = HomeFeedItem("short_drama:drama-1", "short_drama", "drama-1", "Real drama title", "", true)
    @Test fun pausedPreviewDoesNotUseElapsedTimeAndNaturalEndNotifiesOnce() {
        var playing by androidx.compose.runtime.mutableStateOf(false)
        val finishes=java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {DramaPreviewSurface("android.resource://yumo.achat.app/raw/drama_test_video",playing,true,
            onProgress={},onFinished={finishes.incrementAndGet()},onError={error("Local preview failed")})}
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle {assertEquals(0,finishes.get());playing=true}
        compose.waitUntil(15_000){finishes.get()==1}
        compose.runOnIdle{playing=false}
        compose.runOnIdle{playing=true}
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle{assertEquals(1,finishes.get())}
    }
    @Test fun shortPosterOpensCorrectDramaPlayerAndAcksAbsoluteDisplayedIndex() {
        val gateway = HomeScreenTestGateway(drama)
        var opened: String? = null
        compose.setContent { ZorvHomeFeed(gateway, 0, false, true, {}, {}, {}, {}, {}, { _, _ -> }, onOpenDrama = { opened = it }) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Watch Full Drama").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Real drama title").assertIsDisplayed()
        compose.onAllNodesWithText("SPICY").assertCountEquals(0)
        compose.waitUntil(5_000) { gateway.ackIndices.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(5), gateway.ackIndices); assertEquals(0, gateway.previewRequests) }
        compose.onNodeWithText("Watch Full Drama").performClick()
        compose.runOnIdle { assertEquals("drama-1", opened) }
    }
    @Test fun expiredPreviewNeverUsesOldGrantAsMediaOrRequestsFullPlayback() {
        val gateway = HomeScreenTestGateway(drama)
        compose.setContent { ShortDramaHomeCard(drama.copy(preview = HomeFeedPreview("/api/v1/short-dramas/previews/old", "2020-01-01T00:00:00Z")),
            gateway, true, true, {}, {}) }
        compose.onNodeWithText("Preview expired. Refresh content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, gateway.previewRequests) }
    }

    @Test fun pendingRightsKeepPreviewOnPosterUntilEpisodeIsKnown() {
        val ready=kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway=object:HomeScreenTestGateway(drama) {
            override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {ready.await();return super.shortDramaDetail(dramaId)}
            override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview {previewRequests++;return ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",Instant.now().plusSeconds(300).toString())}
        }
        val item=drama.copy(preview=HomeFeedPreview("/preview",Instant.now().plusSeconds(300).toString()))
        compose.setContent{ShortDramaHomeCard(item,gateway,true,true,{},{})}
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.waitUntil(5000){gateway.previewRequests>0}
        compose.onAllNodes(hasTestTag("template-video-frame") or hasTestTag("template-video-loading")).assertCountEquals(0)
        compose.runOnIdle{ready.complete(Unit)}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun resolvedPreviewRendersRealVideoFrames() {
        val gateway = object : HomeScreenTestGateway(drama) {
            override suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview {
                previewRequests++
                return ResolvedDramaPreview(
                    "android.resource://yumo.achat.app/raw/drama_test_video",
                    Instant.now().plusSeconds(300).toString(),
                )
            }
        }
        val item = drama.copy(
            preview = HomeFeedPreview(
                "/api/v1/short-dramas/previews/grant",
                Instant.now().plusSeconds(300).toString(),
            ),
        )
        compose.setContent { ShortDramaHomeCard(item, gateway, true, true, {}, {}) }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("template-video-frame").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, gateway.previewRequests) }
    }
}
