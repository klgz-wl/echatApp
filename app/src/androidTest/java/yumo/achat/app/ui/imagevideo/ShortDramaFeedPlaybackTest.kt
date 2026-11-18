package yumo.achat.app.ui.imagevideo

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

class ShortDramaFeedPlaybackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun expiredPreviewAutomaticallyPlaysEntitledEpisode() = verifyExpiredPreview(false)
    @Test fun resolverExpiredGrantAutomaticallyPlaysEntitledEpisode() = verifyExpiredPreview(true)

    @Test fun expiredPreviewDoesNotUnlockLockedEpisode() = verifyExpiredPreview(false, locked = true)
    @Test fun expiredResolvedMediaAutomaticallyPlaysEntitledEpisode() = verifyExpiredPreview(true, mediaExpired = true)

    private fun verifyExpiredPreview(resolverExpires: Boolean, locked: Boolean = false, mediaExpired: Boolean = false) {
        val preview = HomeFeedPreview("/preview", if (resolverExpires) java.time.Instant.now().plusSeconds(300).toString() else "2020-01-01T00:00:00Z")
        val item = HomeFeedItem("short_drama:expired", "short_drama", "expired", "Expired preview drama", "", false, preview = preview)
        val gateway = object : PlayerScreenGateway(locked = locked) {
            var acknowledged = false
            override suspend fun homeFeed(requestId: String) = HomeFeedBatch("batch", 0, if (acknowledged) emptyList() else listOf(item))
            override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) { acknowledged = true }
            override suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview {
                if (mediaExpired) return ResolvedDramaPreview("https://example.test/expired.mp4", "2020-01-01T00:00:00Z")
                if (resolverExpires) throw HomeFeedServiceException("GRANT_EXPIRED")
                error("Expired feed grants must not be resolved")
            }
        }
        val vm = ShortDramaPlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            ZorvHomeFeed(gateway, 0, false, true, {}, {}, {}, {}, {}, { _, _ -> },
                onOpenDrama = {}, playbackViewModel = vm, ownerKey = "owner", userId = "test-owner")
        }
        try {
            if (locked) {
                compose.onNodeWithText("Open the drama to view episode access").assertIsDisplayed()
                compose.onNodeWithTag("drama-video-frame").assertDoesNotExist()
            } else {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithText("Preview expired. Refresh content").assertDoesNotExist()
            compose.runOnIdle { assertEquals(if (locked) 0 else 1, gateway.plays); assertEquals(0, gateway.unlocks) }
        } finally { compose.runOnIdle { vm.release() } }
    }

    @Test fun visibleEntitledEpisodeRendersVideoAndContinuesInFullPlayerWithoutReplaying() {
        val item = HomeFeedItem("short_drama:drama", "short_drama", "drama", "Real drama", "", false)
        val gateway = object : PlayerScreenGateway() {
            var acknowledged = false
            override suspend fun homeFeed(requestId: String) = HomeFeedBatch("batch", 0, if (acknowledged) emptyList() else listOf(item))
            override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) { acknowledged = true }
        }
        val vm = ShortDramaPlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            var opened by remember { mutableStateOf<String?>(null) }
            ZorvHomeFeed(gateway, 0, false, true, {}, {}, {}, {}, {}, { _, _ -> }, visible = opened == null,
                onOpenDrama = { opened = it }, playbackViewModel = vm, ownerKey = "owner", userId = "test-owner")
            opened?.let { id -> ShortDramaPlayerRoute(id, "test-owner", "owner", gateway, vm, { opened = null }, {}, {}) }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, gateway.plays) }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText(gateway.description).assertIsDisplayed()
        compose.onNodeWithText("Less").performClick()
        compose.onNodeWithText("Watch Full Drama").performClick()
        compose.onNodeWithText("EP.3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, gateway.plays) }
        compose.onNodeWithContentDescription("Back to Home").performClick()
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, gateway.plays); vm.release() }
    }
}
