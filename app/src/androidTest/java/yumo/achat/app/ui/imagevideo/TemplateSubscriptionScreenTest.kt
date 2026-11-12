package yumo.achat.app.ui.imagevideo

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.theme.AchatTheme

class TemplateSubscriptionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val plans = listOf(SubscriptionPlan("month", "MONTHLY", "$8.00", "Renews monthly"),
        SubscriptionPlan("year", "YEARLY", "$49.00", "Renews yearly"),
        SubscriptionPlan("week", "WEEKLY", "$3.00", "Renews weekly"))
    @Test fun templatePaywallKeepsSubscribeVisibleAndCloseReturnsToSource() {
        var closed = 0
        var purchased: String? = null
        compose.setContent {
            AchatTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF090615))) {
                    SubscriptionScreen(plans, false, null, {}, { closed++ }, { purchased = it }, templateEntry = true)
                }
            }
        }
        compose.onNodeWithText("Premium").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close subscription").assertIsDisplayed()
        compose.onNodeWithText("SUBSCRIBE").assertIsDisplayed()
        // Capture the actual Compose result for comparison with the referenced Figma overlay.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "template_subscription_overlay.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("WEEKLY").performScrollTo().performClick()
        compose.onNodeWithText("SUBSCRIBE").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("week", purchased) }
        compose.onNodeWithContentDescription("Close subscription").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun paymentFeedbackRemainsVisibleBesideDisabledFixedAction() {
        compose.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, {}, {}, templateEntry = true,
                purchaseBusy = true, purchaseMessage = "Waiting for Google Play") }
        }
        compose.onNodeWithText("Waiting for Google Play").assertIsDisplayed()
        compose.onNodeWithText("PROCESSING…").assertIsDisplayed().assertIsNotEnabled()
    }
    @Test fun useTemplateButtonContainsOnlyItsActionLabel() {
        var clicks = 0
        compose.setContent { AchatTheme { TemplateButton { clicks++ } } }
        compose.onNodeWithText("USE THIS TEMPLATE").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onAllNodes(hasText("", substring = true)).assertCountEquals(1)
    }
}
