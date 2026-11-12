package yumo.achat.core.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubscriptionCancelCleanupTest {
    @Test fun cancelClearsPendingBeforeUnlockAndRestoresPlanSelection() = runBlocking {
        val calls = mutableListOf<String>()
        val admission = BillingPurchaseAdmission()
        val lease = admission.tryAcquire()!!
        var state = SubscriptionPurchaseUiState(stage = SubscriptionPurchaseStage.Purchasing, productId = "monthly", hasUnresolvedOrder = true)
        finishCancelledSubscription(
            clearPending = { assertNull(admission.tryAcquire()); calls += "clear" },
            release = { calls += "unlock"; lease.close() },
            publish = {
                calls += "cancelled"
                state = state.copy(stage = SubscriptionPurchaseStage.Cancelled, hasUnresolvedOrder = false, canChangePlan = true)
            },
        )
        assertEquals(listOf("clear", "unlock", "cancelled"), calls)
        assertEquals("monthly", state.productId)
        assertEquals(SubscriptionCheckoutAction.Buy, subscriptionCheckoutAction(state, "weekly"))
        assertNotNull(admission.tryAcquire()?.also { it.close() })
    }
    @Test fun failedCleanupStillReleasesAdmissionButDoesNotPublishFalseSuccess() = runBlocking {
        val admission = BillingPurchaseAdmission(); val lease = admission.tryAcquire()!!
        var published = false
        try {
            finishCancelledSubscription({ error("disk unavailable") }, { lease.close() }, { published = true })
            fail("cleanup failure hidden")
        } catch (_: IllegalStateException) { }
        assertFalse(published)
        assertNotNull(admission.tryAcquire()?.also { it.close() })
    }
}
