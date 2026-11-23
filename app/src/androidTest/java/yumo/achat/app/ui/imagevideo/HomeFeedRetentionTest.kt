package yumo.achat.app.ui.imagevideo

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

class HomeFeedRetentionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun subscriptionRecreationKeepsTheSameCompletedDramaBeyondFirstPage() {
        var vip=false
        var loads=0
        val g=object:PlayerScreenGateway(locked=true) {
            override suspend fun homeFeed(requestId:String):HomeFeedBatch {
                loads++
                return HomeFeedBatch("retained-batch",0,(1..3).map { n ->
                    HomeFeedItem("d$n","short_drama","d$n","Drama $n","",true,preview=HomeFeedPreview("/preview",java.time.Instant.now().plusSeconds(3600).toString()))
                })
            }
            override suspend fun resolveDramaPreview(resolverUrl:String)=ResolvedDramaPreview("android.resource://yumo.achat.app/raw/drama_test_video",java.time.Instant.now().plusSeconds(3600).toString())
            override suspend fun shortDramaDetail(dramaId:String)=ShortDramaDetail(dramaId,dramaId,"",emptyList(),listOf(
                ShortDramaEpisode("ep",1,"",5,true,false,if(vip)"EPISODE_LOCKED" else "VIP_REQUIRED")),1)
        }
        fun mount() {
            compose.activityRule.scenario.onActivity { activity ->
                val vm=ViewModelProvider(activity)[HomeFeedViewModel::class.java]
                activity.setContent { ZorvHomeFeed(g,0,vip,true,{},{},{},{},{},{_,_->},
                    ownerKey="owner",feedViewModel=vm,onOpenDrama={}) }
            }
        }
        mount()
        compose.waitUntil(5000){compose.onAllNodesWithText("Drama 1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("zorv-home-pager").performTouchInput {swipeUp()}
        compose.onNodeWithTag("zorv-home-pager").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Drama 2"))
        compose.waitUntil(15000){compose.onAllNodesWithText("Subscribe to unlock full episodes").fetchSemanticsNodes().isNotEmpty()}
        vip=true
        compose.activityRule.scenario.recreate()
        mount()
        compose.onNodeWithTag("zorv-home-pager").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Drama 2"))
        compose.waitUntil(10000){compose.onAllNodesWithText("Watch Full Series").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {assertEquals(1,loads);assertEquals(0,g.plays);assertEquals(0,g.unlocks)}
    }
}
