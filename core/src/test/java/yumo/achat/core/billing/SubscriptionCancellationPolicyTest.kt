package yumo.achat.core.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionCancellationPolicyTest {
    @Test fun legacySavedRequestsKeepOriginalPricingWhileNewRequestsUseExplicitStandardMode() {
        assertEquals(SubscriptionOrderPricing.Legacy, subscriptionOrderPricing(null))
        assertEquals(SubscriptionOrderPricing.Standard, subscriptionOrderPricing("standard"))
        assertEquals(null, subscriptionOrderPricing("unknown"))
    }
    @Test fun onlyDefinitiveUncreatedMatchingRequestCanBeArchived() {
        fun decision(operation: String = "create_order", code: Int? = 409,
            reason: String? = "PAYMENT_PRODUCT_UNAVAILABLE", hasOrder: Boolean = false, sameRequest: Boolean = true) =
            canArchiveRejectedSubscriptionCreation(operation, code, reason, hasOrder, sameRequest)
        assertEquals(true, decision())
        assertEquals(false, decision(operation = "initialize_payment"))
        assertEquals(false, decision(code = null, reason = null))
        assertEquals(false, decision(code = 500))
        assertEquals(false, decision(reason = "PAYMENT_ORDER_NOT_INITIALIZABLE"))
        assertEquals(false, decision(hasOrder = true))
        assertEquals(false, decision(sameRequest = false))
    }
    @Test fun explicitCancelAllowsSwitchOnlyWhenPlayHasNoPurchaseAndBackendUnpaid() {
        assertEquals(SubscriptionRetryDecision.Replaceable, subscriptionRetryDecision(true, "pending", false))
    }
    @Test fun legacyOrNonCancelFailureAllowsOnlySamePlanReplay() {
        assertEquals(SubscriptionRetryDecision.SamePlanOnly, subscriptionRetryDecision(null, "pending", false))
        assertEquals(SubscriptionRetryDecision.SamePlanOnly, subscriptionRetryDecision(false, "pending", false))
    }
    @Test fun ownedOrPendingStorePurchaseAlwaysBlocksSwitchEvenAfterCancel() {
        assertEquals(SubscriptionRetryDecision.Blocked, subscriptionRetryDecision(true, "pending", true))
        assertEquals(SubscriptionRetryDecision.Blocked, subscriptionRetryDecision(true, "cancelled", true))
    }
    @Test fun paidUnfulfilledOrFulfilledMustBeReconciledNotReplaced() {
        assertEquals(SubscriptionRetryDecision.Blocked, subscriptionRetryDecision(true, "paid", false))
    }
    @Test fun failedQueryOrUnknownBackendNeverEnablesReplayOrReplacement() {
        assertEquals(SubscriptionRetryDecision.Blocked, subscriptionRetryDecision(true, "pending", false, false))
        assertEquals(SubscriptionRetryDecision.Blocked, subscriptionRetryDecision(true, "", false))
    }
    @Test fun confirmedTerminalBackendAndNoStorePurchasePermitReplacement() {
        for (status in listOf("failed", "cancelled", "expired")) {
            assertEquals(SubscriptionRetryDecision.Replaceable, subscriptionRetryDecision(null, status, false))
        }
    }
}
