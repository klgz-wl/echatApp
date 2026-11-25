package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*
import java.io.File

class DramaSubtitleSyncTest {
    @get:Rule val compose=createComposeRule()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private fun controller(gateway:PlayerScreenGateway)=ShortDramaPlaybackController(gateway,scope,object:DramaUnlockStore {
        override fun pending(dramaId:String,episodeId:String,amount:Int):DramaUnlockIntent=error("No charge")
        override fun clear(intent:DramaUnlockIntent)=Unit
    })
    @Test fun pendingCaptionHoldsVideoThenTimedCuesFollowSeekAndClearAtEnd() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.cacheDir,"timed-subtitles.vtt").apply {writeText(
            "WEBVTT\n\n00:00.000 --> 00:00.800\nFirst synchronized cue\n\n00:01.000 --> 00:10.000\nSecond synchronized cue\n")}
        val ready=CompletableDeferred<Unit>()
        val gateway=object:PlayerScreenGateway(){
            override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).let {it.copy(episodes=it.episodes.take(1),episodeCount=1)}
            override suspend fun resolveDramaSubtitle(resolverUrl:String):ResolvedDramaPreview {
                ready.await();return ResolvedDramaPreview(android.net.Uri.fromFile(file).toString(),"2030-01-01T00:00:00Z")
            }
        }
        val state=controller(gateway)
        try {
            compose.setContent{ShortDramaPlayerScreen(state,{},{},{})}
            compose.runOnIdle{state.start("drama");state.setPlaying(false)}
            compose.waitUntil(15000){compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(5000){state.durationMs>0}
            compose.runOnIdle{state.selectSubtitle("en");state.setPlaying(true)}
            Thread.sleep(1200)
            compose.runOnIdle{assertTrue("Video must wait for selected subtitle readiness",state.positionMs<500);state.setPlaying(false);ready.complete(Unit)}
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("First synchronized cue").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("drama-seek-control").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.65f)}
            compose.waitUntil(5000){compose.onAllNodesWithContentDescription("Second synchronized cue").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{assertFalse(state.playing);assertEquals(1,gateway.plays);state.setPlaying(true)}
            compose.waitUntil(15000){state.completed}
            compose.onNodeWithContentDescription("Second synchronized cue").assertDoesNotExist()
            compose.runOnIdle{assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
        } finally {compose.runOnIdle{state.close();scope.cancel()};file.delete()}
    }
}
