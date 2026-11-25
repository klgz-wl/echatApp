package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.HomeFeedItem

class HomeTemplateVideoBufferTest {
    @get:Rule val compose=createComposeRule()
    private fun entry(i:Int)=HomeFeedEntry(HomeFeedItem("v$i","video_template","v$i","Video $i","",false,
        fileUrl="android.resource://yumo.achat.app/raw/drama_test_video"),"batch",i)
    @Test fun currentHasExclusiveColdBudgetAndNextWarmsAfterFirstFrame() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        lateinit var buffer:HomeTemplateVideoBuffer
        val entries=(0..3).map(::entry)
        compose.setContent {}
        compose.runOnIdle {
            buffer=HomeTemplateVideoBuffer(context)
            buffer.update(entries,1,false)
            assertEquals(listOf(entries[1].key),buffer.sourceKeys)
            buffer.update(entries,1,true)
            assertEquals(listOf(entries[1].key,entries[2].key),buffer.sourceKeys)
            buffer.update(entries,2,false)
            assertEquals(listOf(entries[2].key),buffer.sourceKeys)
            buffer.update(emptyList(),0,false)
            assertTrue(buffer.sourceKeys.isEmpty())
            buffer.close()
        }
    }
    @Test fun nativePreloadIsReusedByVisibleTemplatePlayer() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val entry=entry(0)
        lateinit var buffer:HomeTemplateVideoBuffer
        var show by mutableStateOf(false)
        var firstFrame=false
        compose.setContent {
            if(show) CompositionLocalProvider(LocalHomeTemplatePlayback provides HomeTemplatePlayback(buffer,entry.key,onFirstFrame={firstFrame=true})) {
                TemplatePreviewImage(TemplatePreviewMedia.RemoteVideo(entry.item.fileUrl,""),true,"Video",videoPlaybackEnabled=true)
            }
        }
        compose.runOnIdle {buffer=HomeTemplateVideoBuffer(context);buffer.update(listOf(entry),0,false)}
        try {
            compose.waitUntil(15_000){buffer.isBuffered(entry.key)}
            compose.runOnIdle {show=true}
            compose.waitUntil(15_000){firstFrame}
        } finally {
            compose.runOnIdle {show=false};compose.waitForIdle();compose.runOnIdle {buffer.close()}
        }
    }
}
