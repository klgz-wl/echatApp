package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.components.TransientMessage
import yumo.achat.app.ui.components.TransientMessageHost

class TransientMessageHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun messageDisappearsAfterConfiguredDuration() {
        val message = mutableStateOf<TransientMessage?>(
            TransientMessage(id = 1L, text = "Insufficient diamond balance"),
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TransientMessageHost(
                message = message.value,
                durationMillis = 1_000L,
                onDismiss = { dismissedId ->
                    if (message.value?.id == dismissedId) {
                        message.value = null
                    }
                },
            )
        }

        composeRule.onNodeWithText("Insufficient diamond balance").assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_100L)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Insufficient diamond balance").assertCountEquals(0)
    }
}
