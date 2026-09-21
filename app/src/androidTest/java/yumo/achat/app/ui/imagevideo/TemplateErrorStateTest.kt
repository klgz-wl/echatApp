package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TemplateErrorStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun templateFailureShowsErrorAndRetryAction() {
        var retried = false
        composeRule.setContent {
            LiveTemplatePlaceholderCard(
                isLoading = false,
                errorMessage = "HTTP 503",
                onRetry = { retried = true },
            )
        }

        composeRule.onNodeWithText("HTTP 503").assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun successfulEmptyStateCanBeRetried() {
        var retried = false
        composeRule.setContent {
            LiveTemplatePlaceholderCard(
                isLoading = false,
                errorMessage = null,
                onRetry = { retried = true },
            )
        }

        composeRule.onNodeWithText("No live templates").assertIsDisplayed()
        composeRule.onNodeWithText("RETRY").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }
}
