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
import androidx.compose.ui.graphics.asAndroidBitmap

class DramaSubtitleVisibilityTest {
    @get:Rule val compose=createComposeRule()
    @Test fun decodedWebVttAppearsAboveFooterAndTurningOffClearsCaptions() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.cacheDir,"subtitle-visibility.vtt").apply {
            writeText("WEBVTT\n\n00:00.000 --> 01:00.000 line:90%\nVisible native subtitle\n")
        }
        val gateway=object:PlayerScreenGateway(){
            override suspend fun playDramaEpisode(dramaId:String,episodeId:String)=super.playDramaEpisode(dramaId,episodeId).let {
                it.copy(subtitles=it.subtitles+DramaSubtitle("fr","fr","French","vtt","/subtitle"))
            }
            override suspend fun resolveDramaSubtitle(resolverUrl:String)=
                ResolvedDramaPreview(android.net.Uri.fromFile(file).toString(),"2030-01-01T00:00:00Z")
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val state=ShortDramaPlaybackController(gateway,scope,object:DramaUnlockStore {
            override fun pending(dramaId:String,episodeId:String,amount:Int):DramaUnlockIntent=error("No purchase")
            override fun clear(intent:DramaUnlockIntent)=Unit
        })
        try {
            compose.setContent{ShortDramaPlayerScreen(state,{},{},{})}
            compose.runOnIdle{state.start("drama");state.setPlaying(false);state.selectSubtitle("en")}
            compose.waitUntil(15000){compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("Visible native subtitle").fetchSemanticsNodes().isNotEmpty()}
            val captions=compose.onNodeWithTag("drama-subtitle-overlay").fetchSemanticsNode().boundsInRoot
            val footer=compose.onNodeWithTag("drama-player-footer").fetchSemanticsNode().boundsInRoot
            assertTrue("Subtitle viewport must be above the footer",captions.bottom<=footer.top)
            compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap ->
                File(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.let(::File) ?: context.getExternalFilesDir(null),"subtitle-visibility.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
            }
            compose.runOnIdle{state.selectSubtitle("fr")}
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("Visible native subtitle").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{assertEquals("fr",state.selectedSubtitleId)}
            compose.runOnIdle{state.selectSubtitle(null)}
            compose.onNodeWithContentDescription("Visible native subtitle").assertDoesNotExist()
            compose.runOnIdle{assertEquals(1,gateway.plays);assertEquals(0,gateway.unlocks)}
        } finally {compose.runOnIdle{state.close();scope.cancel()};file.delete()}
    }
}
