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
import java.time.Instant

class HomeStartupSchedulingTest {
    @get:Rule val compose=createComposeRule()
    @Test fun entitlementSkipsPreviewAndOwnerChangeRefetchesMetadata() {
        var details=0;var previews=0
        val entries=(0..1).map {HomeFeedItem("d$it","short_drama","d$it","Drama $it","",false,
            preview=HomeFeedPreview("/preview/$it",Instant.now().plusSeconds(600).toString()))}
        val g=object:PlayerScreenGateway(){
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("batch",0,entries)
            override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {details++;return super.shortDramaDetail(dramaId)}
            override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview {previews++;error("Entitled Home must not fetch preview")}
        }
        val vm=ShortDramaPlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        var owner by mutableStateOf("one")
        compose.setContent {ZorvHomeFeed(g,0,false,true,{},{},{},{},{},{_,_->},onOpenDrama={},
            playbackViewModel=vm,ownerKey=owner,userId=owner)}
        compose.waitUntil(15000){compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {assertEquals(3,details);assertEquals(1,g.plays);assertEquals(0,previews);owner="two"}
        compose.waitUntil(15000){details==6 && g.plays==2 && compose.onAllNodesWithTag("drama-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {assertEquals(0,previews);assertEquals(0,g.unlocks);vm.release()}
    }
    @Test fun vipChangeAndForegroundVisibilityDoNotCloseRetainedTemplateBuffer() {
        val item=HomeFeedItem("v","video_template","v","Video","",false,
            fileUrl="android.resource://yumo.achat.app/raw/drama_test_video")
        val g=object:PlayerScreenGateway(){
            var acknowledged=false
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("batch",0,if(acknowledged)emptyList() else listOf(item))
            override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String){acknowledged=true}
        }
        var vip by mutableStateOf(false);var visible by mutableStateOf(true)
        compose.setContent {ZorvHomeFeed(g,0,vip,true,{},{},{},{},{},{_,_->},visible=visible,onOpenDrama={})}
        fun awaitFrame(){compose.waitUntil(15000){compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()}}
        awaitFrame()
        compose.runOnIdle{vip=true}
        awaitFrame()
        compose.runOnIdle{visible=false};compose.waitForIdle()
        compose.runOnIdle{visible=true}
        awaitFrame()
    }
}
