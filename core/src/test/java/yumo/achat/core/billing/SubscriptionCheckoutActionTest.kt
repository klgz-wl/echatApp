package yumo.achat.core.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionCheckoutActionTest {
    @Test fun cancelledConfirmedEmptyOrderCanBuySelectedPlanAgain() {
        assertEquals(SubscriptionCheckoutAction.Buy, subscriptionCheckoutAction(
            SubscriptionPurchaseUiState(stage = SubscriptionPurchaseStage.Cancelled,
                productId = "monthly", hasUnresolvedOrder = false, canRetrySamePlan = true, canChangePlan = true), "monthly"))
    }
    @Test fun unknownResultOnlyChecksExistingOrderEvenWhenAnotherPlanIsSelected() {
        assertEquals(SubscriptionCheckoutAction.Check, subscriptionCheckoutAction(
            SubscriptionPurchaseUiState(stage = SubscriptionPurchaseStage.Error,
                productId = "monthly", hasUnresolvedOrder = true), "weekly"))
    }
    @Test fun interruptedOrderCreationMustResumeOriginalRequestRatherThanCheckMissingOrderId() {
        val state = SubscriptionPurchaseUiState(stage = SubscriptionPurchaseStage.Error,
            error = "subscription_retry_required", productId = "original", hasUnresolvedOrder = true,
            canRetrySamePlan = true, canChangePlan = false)
        assertEquals(SubscriptionCheckoutAction.Resume, subscriptionCheckoutAction(state, "another-plan"))
    }
    @Test fun legacySamePlanOnlyHasExplicitResumeAction() {
        val state = SubscriptionPurchaseUiState(stage = SubscriptionPurchaseStage.Error,
            productId = "monthly", hasUnresolvedOrder = true, canRetrySamePlan = true)
        assertEquals(SubscriptionCheckoutAction.Resume, subscriptionCheckoutAction(state, "weekly"))
    }
    @Test fun unavailableAndOwnedPlansStayBlockedAfterSelectingAnotherPlan() {
        val state = SubscriptionPurchaseUiState(unavailableProductIds = setOf("monthly"), ownedProductIds = setOf("weekly"))
        assertEquals(SubscriptionCheckoutAction.Unavailable, subscriptionCheckoutAction(state, "monthly"))
        assertEquals(SubscriptionCheckoutAction.Owned, subscriptionCheckoutAction(state, "weekly"))
        assertEquals(SubscriptionCheckoutAction.Buy, subscriptionCheckoutAction(state, "yearly"))
    }
}
