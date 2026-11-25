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

/** Locked Home cards use previews; entitled visible cards may play and retain their grant. */
class ShortDramaFeedPlaybackTest {
    @get:Rule val compose=createComposeRule()
    @Test fun expiredPreviewKeepsPosterAndNeverRequestsFormalPlayback() {
        val item=HomeFeedItem("i","short_drama","drama","Real drama","",false,
            preview=HomeFeedPreview("/preview","2020-01-01T00:00:00Z"))
        val gateway=object:PlayerScreenGateway(locked=true){
            var acknowledged=false
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("batch",0,if(acknowledged)emptyList() else listOf(item))
            override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String){acknowledged=true}
        }
        val vm=ShortDramaPlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent{ZorvHomeFeed(gateway,0,false,true,{},{},{},{},{},{_,_->},onOpenDrama={},playbackViewModel=vm,ownerKey="owner",userId="test-owner")}
        compose.onNodeWithText("Preview expired. Refresh content").assertIsDisplayed()
        compose.onNodeWithTag("drama-video-frame").assertDoesNotExist()
        compose.runOnIdle{assertEquals(0,gateway.plays);assertEquals(0,gateway.unlocks);vm.release()}
    }
    @Test fun entitledNoPreviewPlaysWhileVisibleAndReusesGrantOnExplicitWatch() {
        val item=HomeFeedItem("i","short_drama","drama","Real drama","",false)
        val gateway=object:PlayerScreenGateway(){
            var acknowledged=false
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("batch",0,if(acknowledged)emptyList() else listOf(item))
            override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String){acknowledged=true}
        }
        val vm=ShortDramaPlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        var opened by mutableStateOf(false)
        compose.setContent{
            ZorvHomeFeed(gateway,0,false,true,{},{},{},{},{},{_,_->},visible=!opened,
                onOpenDrama={opened=true},playbackViewModel=vm,ownerKey="owner",userId="test-owner")
            if(opened) ShortDramaPlayerRoute("drama","test-owner","owner",gateway,vm,{opened=false},{},{})
        }
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.waitUntil(15000){compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{assertEquals(1,gateway.plays)}
        compose.onNodeWithText("Watch Full Drama").performClick()
        compose.waitUntil(15000){opened && compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{
            assertTrue(opened);assertEquals("episode-3",vm.bind("owner","test-owner","drama",gateway).episode?.episodeId)
            assertEquals(1,gateway.plays);vm.release()
        }
    }
}
