package com.zorv.core.billing

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseRequestCoordinatorTest {

    @Test
    fun `active purchase remains correlated until terminal callback`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val first = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")
        val firstResult = async { first.await() }

        val rejectedSecond = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")
        assertFalse(coordinator.isActive(rejectedSecond))
        assertEquals(BillingResponseCode.ERROR, rejectedSecond.await().responseCode)

        assertTrue(coordinator.complete(first, BillingResult.success(purchase("coins_100"))))
        assertEquals("coins_100", firstResult.await().data?.productId)

        val second = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")
        val matchingSecond = coordinator.findMatching(listOf("coins_500"), "order-2")
        assertTrue(matchingSecond === second)
        assertTrue(coordinator.complete(second, BillingResult.success(purchase("coins_500"))))
        assertEquals("coins_500", second.await().data?.productId)
    }

    @Test
    fun `launch rejection completes current request immediately`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val request = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")
        val rejection = BillingResult.error<BillingPurchase>(
            BillingResponseCode.BILLING_UNAVAILABLE,
            "Launch billing flow failed",
        )

        assertTrue(coordinator.complete(request, rejection))
        assertEquals(
            BillingResponseCode.BILLING_UNAVAILABLE,
            request.await().responseCode,
        )
    }

    @Test
    fun `cancel closes request before a new purchase starts`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val first = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")

        assertTrue(
            coordinator.completeActive(
                BillingResult.error(BillingResponseCode.CANCELLED, "Purchase cancelled by user"),
            ),
        )
        assertEquals(BillingResponseCode.CANCELLED, first.await().responseCode)

        val second = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")
        assertTrue(coordinator.findMatching(listOf("coins_500"), "order-2") === second)
    }

    @Test
    fun `caller cancellation cannot release active Play request`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val first = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")
        val cancelledObserver = async { first.await() }
        yield()
        cancelledObserver.cancelAndJoin()

        val second = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")

        assertTrue(coordinator.isActive(first))
        assertFalse(coordinator.isActive(second))
        assertEquals(BillingResponseCode.ERROR, second.await().responseCode)
    }

    @Test
    fun `concurrent request is rejected without replacing active purchase`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val first = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")

        val second = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")

        assertFalse(coordinator.isActive(second))
        assertEquals(BillingResponseCode.ERROR, second.await().responseCode)
        assertTrue(coordinator.isActive(first))
        assertTrue(coordinator.findMatching(listOf("coins_100"), "order-1") === first)
    }

    @Test
    fun `pending purchase keeps ownership until the same order becomes purchased`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val pending = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")
        val pendingResult = async { pending.await() }

        assertTrue(coordinator.findMatching(listOf("coins_100"), "order-1") === pending)
        val rejectedSecond = coordinator.start("coins_500", BillingProductType.IN_APP, "order-2")
        assertFalse(coordinator.isActive(rejectedSecond))
        assertEquals(BillingResponseCode.ERROR, rejectedSecond.await().responseCode)

        val purchased = purchase("coins_100")
        assertTrue(coordinator.complete(pending, BillingResult.success(purchased)))
        assertEquals(purchased, pendingResult.await().data)
    }

    @Test
    fun `late callback for same product cannot complete a different order`() = runBlocking {
        val coordinator = PurchaseRequestCoordinator()
        val first = coordinator.start("coins_100", BillingProductType.IN_APP, "order-1")
        assertTrue(
            coordinator.complete(
                first,
                BillingResult.error(BillingResponseCode.CANCELLED, "Purchase cancelled"),
            ),
        )

        val second = coordinator.start("coins_100", BillingProductType.IN_APP, "order-2")

        assertNull(coordinator.findMatching(listOf("coins_100"), "order-1"))
        assertTrue(coordinator.findMatching(listOf("coins_100"), "order-2") === second)
    }

    private fun purchase(productId: String) = BillingPurchase(
        orderId = "order-$productId",
        productId = productId,
        purchaseToken = "token-$productId",
        purchaseTime = 1L,
        purchaseState = PurchaseState.PURCHASED,
        isAcknowledged = false,
        signature = "signature",
        originalJson = "{}",
        productType = BillingProductType.IN_APP,
    )
}
