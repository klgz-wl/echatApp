package yumo.achat.app.ui.imagevideo

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.MainActivity

/** Explicit opt-in smoke: opens the Play confirmation window, never confirms payment. */
class LiveSubscriptionCheckoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun openAvailableWeeklyCheckoutWithoutConfirmingPayment() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveSubscriptionCheckout") == "true")
        compose.waitUntil(45_000) { compose.onAllNodesWithText("VIP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("VIP").performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithText("WEEKLY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("WEEKLY").performScrollTo().performClick()
        compose.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var storeWindow = false
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!storeWindow && System.nanoTime() < deadline) {
            storeWindow = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.vending"
            if (!storeWindow) Thread.sleep(250)
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("checkout_diagnostic", "play_confirmation_open=$storeWindow; no payment confirmation performed") })
        assertTrue("Expected Google Play confirmation; no purchase was confirmed", storeWindow)
    }
}
