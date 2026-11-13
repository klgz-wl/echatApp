package yumo.achat.core.billing

import org.junit.Assert.*
import org.junit.Test

class SubscriptionOwnershipRefreshTest {
    private fun purchase(sku: String, state: PurchaseState) = BillingPurchase(null, sku, "internal-token", 0,
        state, false, signature = "", originalJson = "", productType = BillingProductType.SUBS)
    @Test fun successfulEmptyQueryClearsExpiredOwnershipWithoutWaitingForAppRestart() {
        assertTrue(activeSubscriptionOwnership(mapOf("monthly" to "play.month"), emptyList()).isEmpty())
    }
    @Test fun pendingAndPurchasedStayBlockedButOtherSkusCannotBlockThisPlan() {
        val known = mapOf("monthly" to "play.month", "weekly" to "play.week")
        assertEquals(setOf("monthly"), activeSubscriptionOwnership(known,
            listOf(purchase("play.month", PurchaseState.PURCHASED))).keys)
        assertEquals(setOf("weekly"), activeSubscriptionOwnership(known,
            listOf(purchase("play.week", PurchaseState.PENDING))).keys)
        assertThrows(IllegalStateException::class.java) { activeSubscriptionOwnership(known, listOf(purchase("play.month", PurchaseState.UNSPECIFIED))) }
    }
}
