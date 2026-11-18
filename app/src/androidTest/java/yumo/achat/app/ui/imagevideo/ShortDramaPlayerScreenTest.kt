package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*
import java.time.Instant

internal open class PlayerScreenGateway(var locked: Boolean = false) : ShortDramaPlaybackGateway {
    var plays = 0; var unlocks = 0; var subtitles = 0
    val description = "A real description from the API that is long enough to overflow one line and must be shown completely in the More dialog."
    private fun expiry() = Instant.now().plusSeconds(300).toString()
    override suspend fun shortDramaDetail(dramaId: String) = ShortDramaDetail(dramaId, "Real drama", "", listOf("Mystery"), listOf(
        ShortDramaEpisode("episode-3", 3, "Third episode", if (locked) 5 else 0, false, !locked, if (locked) "EPISODE_LOCKED" else "allowed"),
        ShortDramaEpisode("episode-4", 4, "Fourth episode", 0, false, true, "allowed")), 2, description)
    private fun grant() = DramaPlaybackGrant("auth", "grant", "/api/v1/short-dramas/media/grant", expiry(), listOf(DramaSubtitle("en", "en", "English", "vtt", "/subtitle")))
    override suspend fun playDramaEpisode(dramaId: String, episodeId: String): DramaPlaybackGrant { plays++; return grant() }
    override suspend fun refreshDramaGrant(grant: String) = grant()
    override suspend fun resolveDramaMedia(resolverUrl: String) = ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video", expiry())
    override suspend fun resolveDramaSubtitle(resolverUrl: String): ResolvedDramaPreview { subtitles++; return ResolvedDramaPreview("https://example.test/subtitle.vtt", expiry()) }
    override suspend fun unlockDramaEpisode(dramaId: String, episodeId: String, confirmedAmount: Int, idempotencyKey: String) { assertEquals(5, confirmedAmount); unlocks++; locked = false }
    override suspend fun homeFeed(requestId: String) = HomeFeedBatch("empty", 0, emptyList())
    override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) = Unit
    override suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview = error("not used")
}

class ShortDramaPlayerScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: ShortDramaPlaybackController? = null
    private fun create(gateway: PlayerScreenGateway): ShortDramaPlaybackController = ShortDramaPlaybackController(gateway, scope, object : DramaUnlockStore {
        var intent: DramaUnlockIntent? = null
        override fun pending(dramaId: String, episodeId: String, amount: Int) = intent ?: DramaUnlockIntent(dramaId, episodeId, amount, "stable-key").also { intent = it }
        override fun clear(intent: DramaUnlockIntent) { this.intent = null }
    }).also { controller = it }
    @After fun release() { compose.runOnIdle { controller?.close(); scope.cancel() } }

    @Test fun actualEpisodeRendersFramesAndPauseDoesNotRequestAnotherPlay() {
        val gateway = PlayerScreenGateway(); val state = create(gateway)
        compose.setContent { ShortDramaPlayerScreen(state, {}, {}, {}) }
        compose.runOnIdle { state.start("drama") }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("EP.3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause episode").performClick()
        compose.onNodeWithContentDescription("Play episode").assertIsDisplayed()
        compose.runOnIdle { assertFalse(state.playing); assertEquals(1, gateway.plays) }
        compose.onNodeWithContentDescription("Play episode").performClick()
        compose.runOnIdle { assertTrue(state.playing); assertEquals(1, gateway.plays) }
    }
    @Test fun moreShowsRealDescriptionAndEpisodePickerChangesEpisode() {
        val gateway = PlayerScreenGateway(); val state = create(gateway)
        compose.setContent { ShortDramaPlayerScreen(state, {}, {}, {}, videoContent = { _, modifier, _, _ -> Box(modifier) }) }
        compose.runOnIdle { state.start("drama") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("EP.3").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot(useUnmergedTree = true).printToLog("DramaUITest")
        compose.onNodeWithTag("drama-description-line").assertIsDisplayed()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText(gateway.description).assertIsDisplayed()
        compose.onNodeWithText("Less").assertIsDisplayed().performClick()
        compose.onNodeWithText("More").assertIsDisplayed()
        compose.onNodeWithText("Episodes").performClick()
        compose.onNodeWithText("4").performClick()
        compose.onNodeWithText("EP.4").assertIsDisplayed()
        compose.runOnIdle { assertEquals("episode-4", state.episode?.episodeId); assertEquals(0L, state.positionMs) }
    }
    @Test fun lockedEpisodeNeedsVisibleCoinConfirmationBeforeUnlock() {
        val gateway = PlayerScreenGateway(locked = true); val state = create(gateway)
        compose.setContent { ShortDramaPlayerScreen(state, {}, {}, {}, videoContent = { _, modifier, _, _ -> Box(modifier) }) }
        compose.runOnIdle { state.start("drama") }
        compose.onNodeWithText("Unlock for 5 coins").performClick()
        compose.runOnIdle { assertEquals(0, gateway.unlocks); assertEquals(0, gateway.plays) }
        compose.onNodeWithText("Unlock episode 3 for 5 coins?").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals(1, gateway.unlocks); assertEquals(1, gateway.plays) }
    }
}
