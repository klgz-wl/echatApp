package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.theme.AchatTheme

class TemplateButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingButtonDoesNotRenderSyntheticDiamondCost() {
        composeRule.setContent {
            AchatTheme {
                TemplateButton(price = null, enabled = false, onClick = {})
            }
        }

        composeRule.onNodeWithText("USE THIS TEMPLATE").assertIsDisplayed()
        composeRule.onNodeWithText("22").assertDoesNotExist()
    }

    @Test
    fun liveTemplateButtonRendersBackendDiamondCost() {
        composeRule.setContent {
            AchatTheme {
                TemplateButton(price = 9, enabled = true, onClick = {})
            }
        }

        composeRule.onNodeWithText("9").assertIsDisplayed()
    }
}
