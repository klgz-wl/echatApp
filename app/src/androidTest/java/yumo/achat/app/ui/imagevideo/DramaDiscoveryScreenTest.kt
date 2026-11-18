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

private class DiscoveryTestGateway : ShortDramaDiscoveryGateway {
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
    @Test fun rankingExpandsFromFourToTenAndRoutesRealEpisode() {
        var opened:Pair<String,String?>?=null
        val gateway=DiscoveryTestGateway(); val details=PlayerScreenGateway()
        compose.setContent {ShortDramaDiscoveryScreen(gateway,0,false,{},{},{},{},{},{d,e->opened=d to e},details)}
        compose.waitForIdle()
        snapshot("drama-home")
        compose.onNodeWithTag("drama-more-Top 10 Dramas").performScrollTo().performClick()
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
