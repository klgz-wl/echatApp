package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupRetryUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun exhaustedProfileRefreshProvidesExplicitRetryAndClearsWhenRestarted() {
        val failed = mutableStateOf(true)
        var profileRetries = 0
        composeRule.setContent {
            Box {
                AttributionProfileRetry(failed.value) {
                    profileRetries++
                    failed.value = false
                }
            }
        }
        composeRule.onNodeWithText("RETRY").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, profileRetries) }
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
    }

    @Test fun noProfileFailureShowsNoRetry() {
        composeRule.setContent {
            Box {
                AttributionProfileRetry(false) { error("unexpected retry") }
            }
        }
        composeRule.onNodeWithText("RETRY").assertDoesNotExist()
    }
}
