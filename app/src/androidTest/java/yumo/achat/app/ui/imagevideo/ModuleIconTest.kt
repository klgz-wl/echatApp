package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test

class ModuleIconTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun conversationLogAndFeedbackUseTheirFigmaIconSemantics() {
        composeRule.setContent {
            Column {
                ModuleGlyph(ModuleIcon.ConversationLog)
                ModuleGlyph(ModuleIcon.Feedback)
                ModuleGlyph(ModuleIcon.Edit)
            }
        }

        composeRule.onNodeWithContentDescription("Conversation log icon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Feedback inbox icon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit name icon").assertIsDisplayed()
    }
}
