package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*
import java.time.Instant

class DramaHomeGateScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun snapshot(name:String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"$name.png")
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private val title="Gate drama"
    private val item=HomeFeedItem("gate","short_drama","gate",title,"android.resource://yumo.achat.app/drawable/hero_portrait",true,
        preview=HomeFeedPreview("/preview",Instant.now().plusSeconds(3600).toString()))
    private fun gateway(vip:Boolean,amount:Int,allowed:Boolean)=object:PlayerScreenGateway() {
        override suspend fun shortDramaDetail(dramaId:String)=ShortDramaDetail(dramaId,title,"",emptyList(),listOf(
            ShortDramaEpisode("ep",1,"",amount,vip,allowed,if(allowed)"allowed" else if(vip)"VIP_REQUIRED" else "EPISODE_LOCKED")),1)
        override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview {kotlinx.coroutines.awaitCancellation()}
    }
    @Test fun vipNonSubscriberGetsSingleSubscribeCtaAndNoPlayerOrTopUpAction() {
        var subscribe=0;var topup=0;var player=0
        val g=gateway(true,5,false)
        compose.setContent{ShortDramaHomeCard(item,g,true,true,{player++},{},
            isVip=false,onSubscribe={subscribe++},onTopUp={topup++},completed=true)}
        compose.onNodeWithText("Subscribe to unlock full episodes").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Watch Full Series").assertCountEquals(0)
        compose.runOnIdle{assertEquals(1,subscribe);assertEquals(0,topup);assertEquals(0,player)}
    }
    @Test fun subscribedPaidEpisodeWatchFullSeriesGoesDirectlyToTopUp() {
        var subscribe=0;var topup=0;var player=0
        val g=gateway(true,5,false)
        compose.setContent{ShortDramaHomeCard(item,g,true,true,{player++},{},
            isVip=true,onSubscribe={subscribe++},onTopUp={topup++},completed=true)}
        compose.onNodeWithText("Watch Full Series").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Subscribe to unlock full episodes").assertCountEquals(0)
        compose.runOnIdle{assertEquals(1,topup);assertEquals(0,subscribe);assertEquals(0,player)}
    }
    @Test fun subscriptionAndTopUpReturnKeepSameCompletedHomeCard() {
        var visible by mutableStateOf(true);var vip by mutableStateOf(false)
        var subscription=0;var topup=0;val ack=mutableListOf<Int>()
        val g=object:PlayerScreenGateway(locked=true) {
            override suspend fun homeFeed(requestId:String)=HomeFeedBatch("gate-batch",0,if(ack.isEmpty())listOf(item) else emptyList())
            override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String){ack+=displayedThroughIndex}
            override suspend fun shortDramaDetail(dramaId:String)=ShortDramaDetail(dramaId,title,item.coverUrl,emptyList(),listOf(
                ShortDramaEpisode("ep",1,"",5,true,false,if(vip)"EPISODE_LOCKED" else "VIP_REQUIRED")),1)
            override suspend fun resolveDramaPreview(resolverUrl:String)=ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",Instant.now().plusSeconds(3600).toString())
        }
        compose.setContent{ZorvHomeFeed(g,0,vip,true,{},{},{},{},{},{_,_->},visible=visible,onOpenDrama={error("Gate must not enter player")},
            onPreviewSubscription={subscription++;visible=false},onPreviewTopUp={topup++;visible=false})}
        compose.waitUntil(15_000){compose.onAllNodesWithText("Subscribe to unlock full episodes").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Subscribe to unlock full episodes").assertIsDisplayed()
        compose.onAllNodesWithText("Load more content").assertCountEquals(0)
        snapshot("home-gate-subscribe")
        compose.onNodeWithText("Subscribe to unlock full episodes").performClick()
        compose.runOnIdle{assertEquals(1,subscription);vip=true;visible=true}
        compose.waitUntil(10_000){compose.onAllNodesWithText("Watch Full Series").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Watch Full Series").assertIsDisplayed()
        compose.onAllNodesWithText("Load more content").assertCountEquals(0)
        snapshot("home-gate-topup")
        compose.onNodeWithText("Watch Full Series").performClick()
        compose.runOnIdle{assertEquals(1,topup);visible=true}
        compose.waitUntil(10_000){compose.onAllNodesWithText("Watch Full Series").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Watch Full Series").assertIsDisplayed()
        compose.runOnIdle{assertEquals(0,g.plays);assertEquals(0,g.unlocks)}
    }
    @Test fun freeEntitledEpisodeDoesNotShowSubscriptionOrCoinGate() {
        val g=gateway(false,0,true)
        compose.setContent{ShortDramaHomeCard(item,g,true,true,{},{},isVip=false,completed=true)}
        compose.onNodeWithText("Watch Full Drama").assertIsDisplayed()
        compose.onAllNodesWithText("Watch Full Series").assertCountEquals(0)
        compose.onAllNodesWithText("Subscribe to unlock full episodes").assertCountEquals(0)
    }
}
