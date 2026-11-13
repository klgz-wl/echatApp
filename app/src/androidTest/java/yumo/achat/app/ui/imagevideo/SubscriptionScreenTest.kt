package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.theme.AchatTheme

class SubscriptionScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val plans = listOf(
        SubscriptionPlan("year", "YEARLY", "$49.00"),
        SubscriptionPlan("month", "MONTHLY", "$8.00"),
        SubscriptionPlan("week", "WEEKLY", "$3.00"),
    )

    @Test fun switchingPlanClearsPreviousResultAndIgnoresLateOldPlanMessage() {
        var message by mutableStateOf("Monthly is already owned")
        var resultProduct by mutableStateOf("month")
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, {}, { product ->
                resultProduct = product
                message = "New result for $product"
            }, purchaseMessage = message, checkoutState = yumo.achat.core.billing.SubscriptionPurchaseUiState(
                stage = yumo.achat.core.billing.SubscriptionPurchaseStage.Error, productId = resultProduct)) }
        }
        composeRule.onNodeWithText("Monthly is already owned").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("WEEKLY").performScrollTo().performClick()
        composeRule.onNodeWithText("Monthly is already owned").assertDoesNotExist()
        composeRule.runOnIdle { message = "Late result for monthly" }
        composeRule.onNodeWithText("Late result for monthly").assertDoesNotExist()
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.onNodeWithText("New result for week").performScrollTo().assertIsDisplayed()
    }

    @Test fun previousSuccessDoesNotPermanentlyBlockFreshOwnershipCheck() {
        var requested: String? = null
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, {}, { requested = it },
                checkoutState = yumo.achat.core.billing.SubscriptionPurchaseUiState(
                    stage = yumo.achat.core.billing.SubscriptionPurchaseStage.Success, productId = "month")) }
        }
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("month", requested) }
        composeRule.onNodeWithText("WEEKLY").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("week", requested) }
        composeRule.onNodeWithText("MONTHLY").performScrollTo().performClick()
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("month", requested) }
    }

    @Test fun cancelledPurchaseAllowsSelectingDuringCloseAndBuyingAfterClose() {
        var closing by mutableStateOf(true)
        var requested: String? = null
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, {}, { requested = it },
                purchaseBusy = closing, planSelectionEnabled = true) }
        }
        composeRule.onNodeWithText("YEARLY").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithText("PROCESSING…").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { closing = false }
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("year", requested) }
    }

    @Test fun selectingAPlanSubscribesToThatExactProduct() {
        var requested: String? = null
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, {}, { requested = it }) }
        }
        composeRule.onNodeWithText("MONTHLY").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("WEEKLY").performScrollTo().performClick()
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("week", requested) }
    }

    @Test fun loadingErrorAndEmptyStatesKeepSubscribeDisabledAndRetryAvailable() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retries = 0
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(emptyList(), loading, error, { retries++ }, {}, {}) }
        }
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { loading = false; error = "Network unavailable" }
        composeRule.onNodeWithText("Network unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, retries); error = null }
        composeRule.onNodeWithText("Subscriptions are currently unavailable.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SUBSCRIBE").performScrollTo().assertIsNotEnabled()
    }

    @Test fun backReturnsToMeAndBusyPurchaseDisablesAction() {
        var backs = 0
        composeRule.setContent {
            AchatTheme { SubscriptionScreen(plans, false, null, {}, { backs++ }, {}, purchaseBusy = true) }
        }
        composeRule.onNodeWithContentDescription("Back to Me").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
        composeRule.onNodeWithText("PROCESSING…").performScrollTo().assertIsNotEnabled()
    }
}
