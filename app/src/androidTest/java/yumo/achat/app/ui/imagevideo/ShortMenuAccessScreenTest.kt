package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.*

class ShortMenuAccessScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun revealingShortKeepsTheCurrentHomeFeedCardAndDoesNotReload() {
        var side by mutableStateOf(false)
        var loads=0;var mounts=0
        val gateway=object:PlayerScreenGateway() {
            override suspend fun homeFeed(requestId:String):HomeFeedBatch {
                loads++
                return HomeFeedBatch("batch",0,(1..3).map {HomeFeedItem("d$it","short_drama","d$it","Card $it","",false)})
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalShortMenuVisible provides side) {
                DisposableEffect(Unit){mounts++;onDispose{}}
                ZorvHomeFeed(gateway,0,false,false,{},{},{},{},{},{_,_->},onOpenDrama={})
            }
        }
        compose.onNode(hasText("SHORT") and isSelectable()).assertDoesNotExist()
        compose.onNodeWithTag("zorv-home-pager").performTouchInput {swipeUp()}
        compose.onNodeWithTag("zorv-home-pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Card 2"))
        compose.runOnIdle {side=true}
        compose.onNode(hasText("SHORT") and isSelectable()).assertIsDisplayed()
        compose.onNodeWithText("HOME").assertIsSelected()
        compose.onNodeWithTag("zorv-home-pager").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Card 2"))
        compose.runOnIdle {assertEquals(1,loads);assertEquals(1,mounts);assertEquals(0,gateway.plays)}
    }
    @Test fun visibilityChangesPreserveEditsAndUnrelatedSelectedTabAndHiddenShortFallsBackToHome() {
        var side by mutableStateOf(false)
        var selected by mutableIntStateOf(5)
        compose.setContent {
            CompositionLocalProvider(LocalShortMenuVisible provides side) {
                var text by remember {mutableStateOf("")}
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(text,{text=it},modifier=Modifier.testTag("retained-editor"))
                    BottomNavigation(selected,{selected=it})
                }
            }
        }
        compose.onNodeWithTag("retained-editor").performTextInput("Keep my draft")
        compose.onNode(hasText("SHORT") and isSelectable()).assertDoesNotExist()
        compose.onNodeWithText("VIDEO").assertIsSelected()
        compose.runOnIdle {side=true}
        compose.onNode(hasText("SHORT") and isSelectable()).assertIsDisplayed().performClick()
        compose.onNode(hasText("SHORT") and isSelectable()).assertIsSelected()
        compose.runOnIdle {side=false}
        compose.onNode(hasText("SHORT") and isSelectable()).assertDoesNotExist()
        compose.onNodeWithText("HOME").assertIsSelected()
        compose.onNodeWithTag("retained-editor").assertTextContains("Keep my draft")
        compose.runOnIdle {selected=5;side=true}
        compose.onNodeWithText("VIDEO").assertIsSelected()
        compose.onNodeWithTag("retained-editor").assertTextContains("Keep my draft")
    }
}
