package yumo.achat.core.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RechargeCheckoutRecoveryTest {
    private fun purchase(state: PurchaseState = PurchaseState.PURCHASED) = BillingPurchase(
        "play-order", "diamonds", "test-token", 0, state, false,
        signature = "", originalJson = "", productType = BillingProductType.IN_APP)

    @Test fun queriedRechargeRequiresExactProfileBeforeCompletingOriginalWait() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("diamonds", BillingProductType.IN_APP, "profile-1")
        assertFalse(coordinator.completeQueriedRecharge(purchase(), null))
        assertFalse(coordinator.completeQueriedRecharge(purchase(), "profile-2"))
        assertTrue(coordinator.isActive(request))
        assertTrue(coordinator.completeQueriedRecharge(purchase(), "profile-1"))
        assertEquals(PurchaseState.PURCHASED, request.await().data?.purchaseState)
    }

    @Test fun genuinePendingIsReturnedAsPendingAndCannotCompleteSubscription() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("diamonds", BillingProductType.IN_APP, "profile-1")
        assertTrue(coordinator.completeQueriedRecharge(purchase(PurchaseState.PENDING), "profile-1"))
        assertEquals(PurchaseState.PENDING, request.await().data?.purchaseState)
        val subscription = coordinator.start("diamonds", BillingProductType.SUBS, "profile-1")
        assertFalse(coordinator.completeQueriedRecharge(purchase(), "profile-1"))
        assertTrue(coordinator.isActive(subscription))
    }

    @Test fun queryTokenBindingNeverAdoptsAnotherSameSkuOrderOrSubscription() {
        val registry = PendingPurchaseRegistry()
        registry.register(PendingPurchaseRecord("diamonds", "profile-1", BillingProductType.IN_APP,
            null, ownerUserId = "owner-1", businessOrderId = "business-order-1"))
        registry.register(PendingPurchaseRecord("diamonds", "profile-2", BillingProductType.SUBS,
            null, ownerUserId = "owner-2"))
        assertNull(registry.attachRechargeQueryToken(listOf("diamonds"), null, "test-token"))
        assertNull(registry.attachRechargeQueryToken(listOf("diamonds"), "profile-2", "test-token"))
        val record = registry.attachRechargeQueryToken(listOf("diamonds"), "profile-1", "test-token")
        assertEquals("owner-1", record?.ownerUserId)
        assertEquals("business-order-1", record?.businessOrderId)
        assertNull(registry.attachRechargeQueryToken(listOf("diamonds"), "profile-1", "different-token"))
    }
}
