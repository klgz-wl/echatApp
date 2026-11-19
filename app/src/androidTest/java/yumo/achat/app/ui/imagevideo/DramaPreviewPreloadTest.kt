package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*
import java.time.Instant

class DramaPreviewPreloadTest {
    @get:Rule val compose=createComposeRule()
    private fun expiry()=Instant.now().plusSeconds(3600).toString()
    @Test fun nativePreloadHandsInMemorySourceToVisiblePlayerAndClearsWindow() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val entry=HomeFeedEntry(HomeFeedItem("preview","short_drama","d","Drama","",true),"batch",1)
        val state=DramaPreviewWarmupState(entry,1).apply {
            detail=ShortDramaDetail("d","Drama","",emptyList(),listOf(ShortDramaEpisode("e",1,"",5,true,false,"VIP_REQUIRED")),1)
            media=ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",expiry())
            expiresAtMs=System.currentTimeMillis()+3_600_000
        }
        lateinit var buffer:DramaPreviewBuffer
        var show by mutableStateOf(false)
        compose.setContent {
            if(show) DramaPreviewSurface(state.media!!.url,true,true,onProgress={},onFinished={},onError={error("Local preload failed")},buffer=buffer,preloadKey=entry.key)
        }
        compose.runOnIdle{buffer=DramaPreviewBuffer(context);buffer.update(listOf(state),0);assertEquals(1,buffer.sourceCount)}
        try {
            compose.waitUntil(15_000){buffer.isBuffered(entry.key)}
            compose.onNodeWithTag("template-video-frame").assertDoesNotExist()
            compose.runOnIdle{show=true}
            compose.waitUntil(15_000){compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()}
            compose.runOnIdle{show=false}
            compose.waitForIdle()
            compose.runOnIdle{buffer.update(emptyList(),0);assertEquals(0,buffer.sourceCount)}
        } finally {compose.runOnIdle{show=false};compose.waitForIdle();compose.runOnIdle{buffer.close()}}
    }
    @Test fun feedNeighborIsPreparedBeforeSwipeAndReusesRequestsOnEntry() {
        val preview=HomeFeedPreview("/preview",expiry())
        val intro=HomeFeedItem("intro","future_kind","intro","Intro","",false)
        val drama=HomeFeedItem("drama","short_drama","d","Prepared drama","",true,preview=preview)
        val ack=mutableListOf<Int>();var details=0;var previews=0
        val gateway=object:PlayerScreenGateway(locked=true) {
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("batch",0,if(1 in ack)emptyList() else listOf(intro,drama))
            override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String){ack+=displayedThroughIndex}
            override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail{details++;return super.shortDramaDetail(dramaId)}
            override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview{previews++;return ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",expiry())}
        }
        compose.setContent{ZorvHomeFeed(gateway,0,false,true,{},{},{},{},{},{_,_->},onOpenDrama={})}
        compose.waitUntil(10_000){previews==1 && details==1}
        compose.runOnIdle{assertFalse(1 in ack);assertEquals(0,gateway.plays);assertEquals(0,gateway.unlocks)}
        compose.onNodeWithTag("zorv-home-pager").performTouchInput{swipeUp()}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{assertEquals(1,details);assertEquals(1,previews);assertEquals(0,gateway.plays);assertEquals(0,gateway.unlocks)}
    }
    @Test fun normalStartupDoesNotFlashPosterWhileWaitingForVideo() {
        val ready=kotlinx.coroutines.CompletableDeferred<Unit>()
        val item=HomeFeedItem("drama","short_drama","d","Loading drama","",true,preview=HomeFeedPreview("/preview",expiry()))
        val gateway=object:PlayerScreenGateway(){
            override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview{ready.await();return ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",expiry())}
        }
        compose.setContent{ShortDramaHomeCard(item,gateway,true,true,{},{})}
        compose.onNodeWithText("Loading drama").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading drama").assertDoesNotExist()
        compose.runOnIdle{ready.complete(Unit)}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("template-video-frame").fetchSemanticsNodes().isNotEmpty()}
    }
}
