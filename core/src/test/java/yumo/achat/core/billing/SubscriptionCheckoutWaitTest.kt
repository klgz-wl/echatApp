package yumo.achat.core.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubscriptionCheckoutWaitTest {
    @Test fun missingCallbackEndsAsUnknownRatherThanCancellation() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "order-1")
        val result = coordinator.awaitSubscriptionResult(request, 1)
        assertTrue(result.isInterruptedSubscriptionCheckout())
        assertNotEquals(BillingResponseCode.CANCELLED, result.responseCode)
        assertTrue(coordinator.isActive(request))
    }
    @Test fun returnedCheckoutCannotInterruptNewerRequestOrConsumable() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val old = coordinator.start("month", BillingProductType.SUBS, "order-1")
        coordinator.complete(old, BillingResult.error(BillingResponseCode.CANCELLED))
        val current = coordinator.start("week", BillingProductType.SUBS, "order-2")
        assertFalse(coordinator.interruptSubscription(old))
        assertTrue(coordinator.isActive(current))
        coordinator.complete(current, BillingResult.error(BillingResponseCode.CANCELLED))
        val coins = coordinator.start("coins", BillingProductType.IN_APP, "order-3")
        assertFalse(coordinator.interruptSubscription(coins))
        assertTrue(coordinator.isActive(coins))
    }
    @Test fun callbackSuccessWinsOverLateReturnSignal() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "order-1")
        val purchase = BillingPurchase("play-order", "month", "test-token", 0,
            PurchaseState.PURCHASED, false, signature = "", originalJson = "",
            productType = BillingProductType.SUBS)
        coordinator.complete(request, BillingResult.success(purchase))
        assertFalse(coordinator.interruptSubscription(request))
        assertEquals(purchase, coordinator.awaitSubscriptionResult(request, 10).data)
    }
    @Test fun foregroundReturnReleasesCapturedWaitWithoutMarkingCancelled() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "order-1")
        assertTrue(coordinator.interruptSubscription(request))
        assertTrue(coordinator.awaitSubscriptionResult(request, 10).isInterruptedSubscriptionCheckout())
        assertTrue(coordinator.isActive(request))
    }
    @Test fun lateCancellationAfterReturnStillCompletesOriginalRequestAndKeepsNextSafe() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "order-1")
        coordinator.interruptSubscription(request)
        assertTrue(coordinator.awaitSubscriptionResult(request, 10).isInterruptedSubscriptionCheckout())
        val blocked = coordinator.start("week", BillingProductType.SUBS, "order-2")
        assertFalse(coordinator.isActive(blocked))
        coordinator.cancelActive { assertTrue(it === request) }
        assertEquals(BillingResponseCode.CANCELLED, request.await().responseCode)
        assertTrue(coordinator.isActive(coordinator.start("week", BillingProductType.SUBS, "order-2")))
    }
    @Test fun queriedPurchaseWithoutCallbackBindsOnlyExactSubscriptionOrder() {
        val registry = PendingPurchaseRegistry()
        registry.register(PendingPurchaseRecord("month", "profile-1", BillingProductType.SUBS,
            null, ownerUserId = "owner", businessOrderId = "order-1"))
        assertNull(registry.attachSubscriptionQueryToken(listOf("month"), "other-profile", "token"))
        assertNull(registry.attachSubscriptionQueryToken(listOf("month"), null, "token"))
        val matched = registry.attachSubscriptionQueryToken(listOf("month"), "profile-1", "token")
        assertEquals("owner", matched?.ownerUserId)
        assertEquals("order-1", registry.findMatching(emptyList(), null, "token")?.businessOrderId)
        assertNull(registry.attachSubscriptionQueryToken(listOf("month"), "profile-1", "other-token"))
    }
    @Test fun confirmedQueryResolvesOriginalWaitButOtherOrdersCannot() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "profile-1")
        coordinator.interruptSubscription(request)
        val purchase = BillingPurchase("play-order", "month", "token", 0,
            PurchaseState.PURCHASED, false, signature = "", originalJson = "", productType = BillingProductType.SUBS)
        assertFalse(coordinator.completeQueriedSubscription(purchase, "other-profile"))
        assertFalse(coordinator.completeQueriedSubscription(purchase, null))
        assertTrue(coordinator.completeQueriedSubscription(purchase, "profile-1"))
        assertEquals(purchase, request.await().data)
        assertFalse(coordinator.isActive(request))
    }
    @Test fun explicitCancelIsPreservedAndNotReclassifiedAsUnknown() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("month", BillingProductType.SUBS, "order-1")
        coordinator.cancelActive { }
        val result = coordinator.awaitSubscriptionResult(request, 10)
        assertEquals(BillingResponseCode.CANCELLED, result.responseCode)
        assertFalse(result.isInterruptedSubscriptionCheckout())
    }
}
