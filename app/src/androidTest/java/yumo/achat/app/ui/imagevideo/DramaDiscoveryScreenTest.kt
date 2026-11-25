package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

private open class DiscoveryTestGateway : ShortDramaDiscoveryGateway {
    override suspend fun shortDramas(page:Int,pageSize:Int,sort:String,tag:String)=DramaPage((0 until pageSize).map {
        ShortDramaSummary("d$it","Drama $it","","e$it",listOf("Mystery"),3,true,it)
    },pageSize,page,pageSize)
    override suspend fun shortDramaHistory(page:Int,pageSize:Int)=DramaPage(listOf(
        DramaHistoryEntry("history","History drama","","last-episode",7,"Seventh",true,""),
        DramaHistoryEntry("gone","Gone drama","","old",2,"",false,"CONTENT_UNAVAILABLE")),2,page,pageSize)
    override suspend fun shortDramaAds()=emptyList<ShortDramaAd>()
}
class DramaDiscoveryScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun snapshot(name:String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"$name.png")
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun returningFromFilteredAllDoesNotFilterTheHomepagePreview() {
        val gateway=object:DiscoveryTestGateway() {
            override suspend fun shortDramas(page:Int,pageSize:Int,sort:String,tag:String)=super.shortDramas(page,pageSize,sort,tag).let { result ->
                if(tag.isEmpty())result else result.copy(items=result.items.map {it.copy(title="Filtered ${it.title}")})
            }
        }
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,false,{},{},{},{},{},{_,_->},PlayerScreenGateway())}
        compose.onNodeWithTag("drama-more-All Short Dramas").performScrollTo().performClick()
        compose.onNodeWithText("Mystery").performClick()
        compose.onNodeWithText("Filtered Drama 0").assertExists()
        compose.onNodeWithContentDescription("Back to Short").performClick()
        compose.onNodeWithTag("drama-more-All Short Dramas").performScrollTo()
        compose.onAllNodesWithText("Filtered Drama 0").assertCountEquals(0)
    }
    @Test fun historyDisplaysEpisodeTitleAndUnavailableReason() {
        compose.setContent {DramaHistoryScreen(DiscoveryTestGateway(),{},{_,_->})}
        compose.onNodeWithText("Seventh").assertIsDisplayed()
        compose.onNodeWithText("This drama is no longer available.").assertIsDisplayed()
    }
    @Test fun bannerDetailFailureHasRetryAndRealInformationWithoutInventedBenefits() {
        var calls=0
        val gateway=object:DiscoveryTestGateway() {
            override suspend fun shortDramaAds()=listOf(ShortDramaAd("ad","drama","A real promotion","Current offer","Real offer information","Subscribe","subscribe"))
        }
        val details=object:PlayerScreenGateway() {
            override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {
                if(calls++==0)error("temporary network failure")
                return super.shortDramaDetail(dramaId).copy(posterUrl="android.resource://yumo.achat.app/drawable/hero_portrait")
            }
        }
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,false,{},{},{},{},{},{_,_->},details)}
        compose.onNodeWithText("Retry banner image").assertIsDisplayed().performClick()
        compose.waitUntil(5000){calls==2 && compose.onAllNodesWithTag("drama-banner-poster-ready").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Retry banner image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Promotion details").performClick()
        compose.onNodeWithText("Real offer information").assertIsDisplayed()
        compose.onAllNodesWithText("1 free episode.").assertCountEquals(0)
    }
    @Test fun homeWithRealBannerDataKeepsPrimarySectionsAndCapturesDesign() {
        val poster="android.resource://yumo.achat.app/drawable/hero_portrait"
        val gateway=object:DiscoveryTestGateway() {
            override suspend fun shortDramaAds()=listOf(ShortDramaAd("ad","drama","Featured series. Start watching","Discover the next episode.","Offer details","Play now","play"))
            override suspend fun shortDramas(page:Int,pageSize:Int,sort:String,tag:String)=super.shortDramas(page,pageSize,sort,tag).let {result->result.copy(items=result.items.map {it.copy(posterUrl=poster)})}
            override suspend fun shortDramaHistory(page:Int,pageSize:Int)=super.shortDramaHistory(page,pageSize).let {result->result.copy(items=result.items.map {it.copy(posterUrl=poster)})}
        }
        val details=object:PlayerScreenGateway(){override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId).copy(posterUrl=poster)}
        var subscriptions=0
        compose.setContent {ShortDramaDiscoveryScreen(gateway,88,false,{},{subscriptions++},{},{},{},{_,_->error("Upgrade must open subscription")},details)}
        compose.waitUntil(5000){compose.onAllNodesWithText("Upgrade to Premium+").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Promotion details").assertIsDisplayed()
        compose.waitUntil(5000){compose.onAllNodesWithTag("drama-banner-poster-ready").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Play now").assertCountEquals(0)
        compose.onNodeWithText("Upgrade to Premium+").performClick()
        compose.runOnIdle {assertEquals(1,subscriptions)}
        snapshot("short-home-banner")
        compose.onNodeWithTag("drama-more-Top 10 Dramas This Week").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drama-discovery-list").performScrollToKey("rank0")
        snapshot("short-home-ranking")
        compose.onNodeWithTag("drama-discovery-list").performScrollToKey("all-section")
        compose.onNodeWithTag("drama-more-All Short Dramas").assertIsDisplayed()
        snapshot("short-home-all")
    }
    @Test fun subscribedUpgradeButtonOpensItsBannerDramaInsteadOfSubscribingAgain() {
        val gateway=object:DiscoveryTestGateway() {
            override suspend fun shortDramaAds()=listOf(ShortDramaAd("ad","banner-drama","Featured","Offer","Info","Subscribe","subscribe"))
        }
        var opened:Pair<String,String?>?=null
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,true,{}, {error("Member must enter playback")},{},{},{},{d,e->opened=d to e},PlayerScreenGateway())}
        compose.onNodeWithText("Upgrade to Premium+").assertDoesNotExist()
        compose.onNodeWithText("Play Now").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals("banner-drama" to null,opened)}
    }
    @Test fun bannerCanAutoAdvanceContinuouslyAndResumeAfterManualSwipe() {
        val gateway=object:DiscoveryTestGateway() {
            override suspend fun shortDramaAds()=(1..3).map {ShortDramaAd("ad$it","drama$it","Banner $it","Offer","Info","Play now","play")}
        }
        val details=object:PlayerScreenGateway(){override suspend fun shortDramaDetail(dramaId:String)=super.shortDramaDetail(dramaId)
            .copy(posterUrl="android.resource://yumo.achat.app/drawable/hero_portrait")}
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,false,{},{},{},{},{},{_,_->},details)}
        compose.waitUntil(5000){compose.onAllNodesWithTag("drama-banner-poster-ready").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.autoAdvance=false
        fun page(value:String)=SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,value)
        compose.onNodeWithTag("drama-banner-pager").assert(page("1/3"))
        compose.mainClock.advanceTimeBy(1900)
        compose.onNodeWithTag("drama-banner-pager").assert(page("1/3"))
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("drama-banner-pager").assert(page("2/3"))
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("drama-banner-pager").assert(page("3/3"))
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("drama-banner-pager").assert(page("1/3"))
        compose.onNodeWithTag("drama-banner-pager").performTouchInput {swipeLeft()}
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("drama-banner-pager").assert(page("2/3"))
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("drama-banner-pager").assert(page("3/3"))
        compose.onNodeWithTag("drama-banner-info-ad3").performClick()
        compose.mainClock.advanceTimeBy(7000)
        compose.onNodeWithTag("drama-banner-pager").assert(page("3/3"))
        compose.onNodeWithText("Close").performClick()
        compose.mainClock.advanceTimeBy(1900)
        compose.onNodeWithTag("drama-banner-pager").assert(page("3/3"))
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("drama-banner-pager").assert(page("1/3"))
        compose.mainClock.autoAdvance=true
    }
    @Test fun bannerDoesNotAdvanceWhileCurrentPosterMetadataIsLoading() {
        val hold=kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls=mutableListOf<String>()
        val gateway=object:DiscoveryTestGateway(){override suspend fun shortDramaAds()=(1..3).map {
            ShortDramaAd("ad$it","drama$it","Banner $it","Offer","Info","Play now","play")}}
        val details=object:PlayerScreenGateway(){override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {
            calls+=dramaId;hold.await()
            return super.shortDramaDetail(dramaId).copy(posterUrl="android.resource://yumo.achat.app/drawable/hero_portrait")
        }}
        compose.setContent {DramaBanner(gateway,details,{_,_->},{},false)}
        compose.waitUntil(5000){calls.size==2}
        compose.mainClock.autoAdvance=false
        compose.mainClock.advanceTimeBy(7000)
        compose.onNodeWithTag("drama-banner-pager").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"1/3"))
        compose.runOnIdle{hold.complete(Unit)}
        compose.mainClock.autoAdvance=true
        compose.waitUntil(5000){compose.onAllNodesWithTag("drama-banner-poster-ready").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun rankingExpandsFromFourToTenAndRoutesRealEpisode() {
        var opened:Pair<String,String?>?=null
        val gateway=DiscoveryTestGateway(); val details=PlayerScreenGateway()
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,false,{},{},{},{},{},{d,e->opened=d to e},details)}
        compose.waitForIdle()
        snapshot("drama-home")
        compose.onNodeWithTag("drama-more-Top 10 Dramas This Week").performScrollTo().performClick()
        compose.onNodeWithTag("expand-top-ten").performScrollTo().assertIsDisplayed()
        snapshot("drama-rank")
        compose.onNodeWithTag("expand-top-ten").performClick()
        compose.onNodeWithTag("drama-discovery-list").performScrollToKey("rank4")
        compose.onNodeWithTag("drama-card-d9").assertIsDisplayed().performClick()
        compose.runOnIdle{assertEquals("d9" to "e9",opened)}
    }
    @Test fun historyUsesActualEpisodeAndDisablesUnavailableEntries() {
        var opened:Pair<String,String?>?=null
        val gateway=DiscoveryTestGateway()
        compose.setContent {DramaHistoryScreen(gateway,{}, {d,e->opened=d to e})}
        compose.onNodeWithText("EP.7").assertIsDisplayed()
        snapshot("drama-history")
        compose.onNodeWithText("Gone drama").assertIsNotEnabled()
        compose.onNodeWithText("History drama").performClick()
        compose.runOnIdle{assertEquals("history" to "last-episode",opened)}
    }
}
