package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
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

    @Test fun simulatedCommentsSendLocallyAndSurviveClosingSheet() {
        val gateway=PlayerScreenGateway();val state=create(gateway)
        compose.setContent{ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle{state.start("drama")}
        compose.onNodeWithContentDescription("Comments").performClick()
        compose.onNodeWithText("Demo comments · visible only on this device").assertIsDisplayed()
        compose.onNodeWithTag("drama-comment-send").assertIsNotEnabled()
        compose.onNodeWithTag("drama-comment-input").performTextInput("   ")
        compose.onNodeWithTag("drama-comment-send").assertIsNotEnabled()
        compose.onNodeWithTag("drama-comment-input").performTextReplacement("A surprising ending!")
        compose.onNodeWithTag("drama-comment-send").performClick()
        compose.onNodeWithText("A surprising ending!").assertIsDisplayed()
        compose.onNodeWithTag("drama-comment-send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close comments").performClick()
        compose.onNodeWithContentDescription("Comments").performClick()
        compose.onNodeWithText("A surprising ending!").assertIsDisplayed()
        compose.runOnIdle{assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun actualEpisodeRendersFramesAndPauseDoesNotRequestAnotherPlay() {
        val gateway = PlayerScreenGateway(); val state = create(gateway)
        compose.setContent { ShortDramaPlayerScreen(state, {}, {}, {}) }
        compose.runOnIdle { state.start("drama") }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("EP.3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause episode").assertDoesNotExist()
        compose.onNodeWithTag("drama-playback-touch").performClick()
        compose.runOnIdle { assertFalse(state.playing) }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithContentDescription("Play episode").assertIsDisplayed()
        compose.runOnIdle { assertFalse(state.playing); assertEquals(1, gateway.plays) }
        compose.onNodeWithContentDescription("Play episode").performClick()
        compose.onNodeWithContentDescription("Pause episode").assertDoesNotExist()
        compose.runOnIdle { assertTrue(state.playing); assertEquals(1, gateway.plays) }
    }
    @Test fun tappingPlayingVideoPausesImmediatelyAndPausedControlPersistsUntilResume() {
        val gateway=PlayerScreenGateway();val state=create(gateway)
        compose.setContent { ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)}) }
        compose.runOnIdle { state.start("drama") }
        compose.onNodeWithContentDescription("Play episode").assertDoesNotExist()
        compose.onNodeWithTag("drama-playback-touch").performClick()
        compose.runOnIdle {assertFalse(state.playing)}
        compose.onNodeWithContentDescription("Play episode").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithContentDescription("Play episode").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play episode").performClick()
        compose.onNodeWithContentDescription("Play episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause episode").assertDoesNotExist()
        compose.onNodeWithTag("drama-playback-touch").performClick()
        compose.onNodeWithTag("drama-playback-touch").performTouchInput {click(androidx.compose.ui.geometry.Offset(20f,center.y))}
        compose.runOnIdle {assertTrue(state.playing);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun thinProgressTrackStillSupportsSeeking() {
        val state=create(PlayerScreenGateway())
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle {state.start("drama");state.updatePosition(1000,20000)}
        compose.onNodeWithTag("drama-seek-track",useUnmergedTree=true).assertHeightIsEqualTo(2.dp)
        compose.onNodeWithTag("drama-seek-control").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {it(.5f)}
        compose.runOnIdle {assertEquals(10000L,state.positionMs)}
    }
    @Test fun homeEpisodeEndDoesNotImmediatelyShowRatingOnWatchFullEntry() {
        val gateway=PlayerScreenGateway();val state=create(gateway)
        compose.runOnIdle {
            state.start("drama");state.updatePosition(20000,20000)
            state.episodeEnded("episode-3",state.grant!!.authorizationId,advance=false)
        }
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.onAllNodesWithText("Send Feedback").assertCountEquals(0)
        compose.runOnIdle {state.start("drama",restartCompleted=true)}
        compose.onAllNodesWithText("Send Feedback").assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(state.completed);assertFalse(state.seriesCompleted)
            assertTrue(state.playing);assertEquals(0L,state.positionMs)
            assertEquals("episode-3",state.episode?.episodeId)
            assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)
        }
        compose.runOnIdle {
            state.episodeEnded("episode-3",state.grant!!.authorizationId)
            state.episodeEnded("episode-4",state.grant!!.authorizationId)
        }
        compose.onNodeWithText("Send Feedback").assertIsDisplayed()
    }
    @Test fun fullPlayerStartsWithoutPosterWhileMediaIsPendingThenRendersVideoDirectly() {
        val ready=CompletableDeferred<Unit>()
        val gateway=object:PlayerScreenGateway() {
            override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).copy(
                posterUrl="android.resource://yumo.achat.app/drawable/hero_portrait")
            override suspend fun resolveDramaMedia(resolverUrl:String):ResolvedDramaPreview {
                ready.await();return super.resolveDramaMedia(resolverUrl)
            }
        }
        val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{})}
        compose.runOnIdle {state.start("drama")}
        compose.runOnIdle {assertNotNull(state.detail);assertNull(state.media);assertTrue(state.loading)}
        compose.onNodeWithContentDescription("Real drama").assertDoesNotExist()
        compose.runOnIdle {ready.complete(Unit)}
        compose.waitUntil(15000){compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("drama-video-frame").assertIsDisplayed()
        compose.onNodeWithContentDescription("Real drama").assertDoesNotExist()
        compose.runOnIdle {assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun subtitleLanguageButtonUsesEaAndHighlightsWhileChoosingOrEnabled() {
        val gateway=PlayerScreenGateway();val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle {state.start("drama")}
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithContentDescription("Subtitles").assertIsSelected()
        compose.onNodeWithText("EA").assertIsDisplayed()
        compose.runOnIdle {assertEquals("en",state.selectedSubtitleId);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithContentDescription("Subtitles").assertIsNotSelected()
    }
    @Test fun muteUsesSlashedSpeakerAndChangesVolumeStateWithoutRestartingPlayback() {
        val gateway=PlayerScreenGateway();val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle {state.start("drama");state.updatePosition(4500,20000)}
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.onNodeWithTag("drama-audio-muted",useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle {assertTrue(state.muted);assertEquals(4500L,state.positionMs)}
        compose.onNodeWithContentDescription("Unmute").performClick()
        compose.onNodeWithTag("drama-audio-on",useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle {assertFalse(state.muted);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun episodeSheetHasFourColumnsCurrentHighlightRealRightsAndCloseWithoutPlaybackChange() {
        val gateway=object:PlayerScreenGateway() {
            override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).copy(episodes=(1..6).map { n ->
                ShortDramaEpisode("episode-$n",n,"",if(n<=2)0 else 30,n>=3,n<=2,if(n<=2)"allowed" else "VIP_REQUIRED")
            },episodeCount=6)
        }
        val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle {state.start("drama");state.updatePosition(4500,20000)}
        compose.onNodeWithText("Episodes").performClick()
        compose.onNodeWithContentDescription("Close episode selector").assertIsDisplayed()
        compose.onNodeWithText("1/6").assertIsDisplayed()
        compose.onNodeWithTag("drama-episode-episode-1").assertIsSelected()
        val cells=(1..4).map {compose.onNodeWithTag("drama-episode-episode-$it").fetchSemanticsNode().boundsInRoot}
        assertTrue(cells.zipWithNext().all {it.first.left<it.second.left && kotlin.math.abs(it.first.top-it.second.top)<1f})
        compose.onAllNodesWithText("Free").assertCountEquals(2)
        val preview=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"drama-episodes-sheet.png")
        compose.waitForIdle()
        preview.outputStream().use {requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithContentDescription("Close episode selector").performClick()
        compose.runOnIdle {assertEquals("episode-1",state.episode?.episodeId);assertEquals(4500L,state.positionMs);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
        compose.onNodeWithText("Episodes").performClick()
        compose.onNodeWithTag("drama-episode-episode-3").performClick()
        compose.runOnIdle {assertEquals(DramaPlaybackAction.VipRequired,state.action);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun nextVipEpisodeUsesFullScreenSubscribeGateAndReturnsToSameEpisode() {
        var subscribed=false
        var opens=0
        val gateway=object:PlayerScreenGateway() {
            override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {
                val detail=super.shortDramaDetail(dramaId)
                return detail.copy(posterUrl="android.resource://yumo.achat.app/drawable/hero_portrait",episodes=detail.episodes.map {if(it.episodeId=="episode-4")
                    it.copy(vip=true,canPlay=subscribed,accessReason=if(subscribed)"allowed" else "VIP_REQUIRED") else it})
            }
        }
        val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{opens++},{},videoContent={_,modifier,_,_->Box(modifier)})}
        compose.runOnIdle {
            state.start("drama")
            state.episodeEnded("episode-3",state.grant!!.authorizationId)
            assertEquals("episode-4",state.episode?.episodeId)
        }
        compose.onNodeWithText("Subscribe to Unlock All Episodes").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        Thread.sleep(300) // Let the device compositor present the asserted gate before visual capture.
        val gatePreview=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"drama-vip-gate.png")
        gatePreview.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Subscribe to Unlock All Episodes").performClick()
        compose.onNodeWithText("Episodes").assertDoesNotExist()
        compose.onNodeWithTag("drama-seek-control").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause episode").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1,opens);assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks);assertNull(state.media)
            state.setVisible(false);subscribed=true;state.setVisible(true)
        }
        compose.onNodeWithText("Subscribe to Unlock All Episodes").assertDoesNotExist()
        compose.onNodeWithText("EP.4").assertIsDisplayed()
        compose.runOnIdle {assertEquals("episode-4",state.episode?.episodeId);assertEquals(2,gateway.plays);assertEquals(0,gateway.unlocks)}
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
    @Test fun vipGatePrecedesDiamondGateAndSubscriptionDoesNotBuyTheEpisode() {
        var vip=false;var subscriptions=0
        val gateway=object:PlayerScreenGateway(locked=true) {
            override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).let {detail ->
                detail.copy(episodes=detail.episodes.map {it.copy(vip=true,amount=5,canPlay=false,accessReason=if(vip)"EPISODE_LOCKED" else "VIP_REQUIRED")})
            }
        }
        val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{subscriptions++},{},videoContent={_,modifier,_,_->Box(modifier)},balance=5)}
        compose.runOnIdle {state.start("drama")}
        compose.onNodeWithText("Subscribe to Unlock All Episodes").assertIsDisplayed().performClick()
        compose.onNodeWithText("Watch Full Episode").assertDoesNotExist()
        compose.runOnIdle {assertEquals(1,subscriptions);assertEquals(0,gateway.unlocks);state.setVisible(false);vip=true;state.setVisible(true)}
        compose.onNodeWithText("Subscribe to Unlock All Episodes").assertDoesNotExist()
        compose.onNodeWithText("Watch Full Episode").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,gateway.unlocks);assertEquals(0,gateway.plays)}
    }
    @Test fun paidNextEpisodeChecksBalanceThenResumesOnlyAfterRechargeSuccess() {
        val gateway=object:PlayerScreenGateway() {
            override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).let {detail ->
                detail.copy(episodes=detail.episodes.map {if(it.episodeId=="episode-4")
                    it.copy(amount=5,canPlay=!locked,accessReason=if(locked)"EPISODE_LOCKED" else "allowed") else it})
            }
        }.apply {locked=true}
        val state=create(gateway)
        var topups=0
        // The first episode is entitled, then its natural end routes to the paid second episode.
        gateway.locked=false
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{topups++},videoContent={_,modifier,_,_->Box(modifier)},balance=4)}
        compose.runOnIdle {
            state.start("drama");gateway.locked=true
            state.episodeEnded("episode-3",state.grant!!.authorizationId)
        }
        compose.onNodeWithText("Watch Full Episode").assertIsDisplayed()
        val paywallPreview=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"drama-paid-gate.png")
        paywallPreview.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Watch Full Episode").performClick()
        compose.runOnIdle {assertEquals(1,topups);assertEquals(0,gateway.unlocks)}
        compose.onNodeWithText("Subscribe to Unlock All Episodes").assertDoesNotExist()
        compose.runOnIdle {state.setVisible(false);state.setVisible(true);assertEquals(0,gateway.unlocks);state.rechargeSucceeded()}
        compose.runOnIdle {assertEquals("episode-4",state.episode?.episodeId);assertEquals(1,gateway.unlocks);assertEquals(2,gateway.plays)}
        compose.onNodeWithText("Watch Full Episode").assertDoesNotExist()
    }
    @Test fun paidEpisodeEnoughBalanceUnlocksDirectlyFromQuotedCta() {
        val gateway=PlayerScreenGateway(locked=true);val state=create(gateway)
        compose.setContent {ShortDramaPlayerScreen(state,{},{},{error("No Top Up")},videoContent={_,modifier,_,_->Box(modifier)},balance=5)}
        compose.runOnIdle {state.start("drama")}
        compose.onNodeWithText("Watch Full Episode").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(1,gateway.unlocks);assertEquals(1,gateway.plays)}
        compose.onNodeWithText("Watch Full Episode").assertDoesNotExist()
    }
}
