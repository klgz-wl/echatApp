package com.zorv.core.billing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PurchaseFulfillmentCoordinatorTest {
    @Test
    fun `concurrent callers share one fulfillment and clear pending before completion`() = runBlocking {
        val operationStarted = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val operationCount = AtomicInteger()
        val clearedTokens = mutableListOf<String>()
        val coordinator = PurchaseFulfillmentCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onFulfilled = clearedTokens::add,
        )

        val first = async {
            coordinator.fulfill("token-1") {
                operationCount.incrementAndGet()
                operationStarted.complete(Unit)
                releaseOperation.await()
                BillingResult.success(Unit)
            }
        }
        operationStarted.await()
        val second = async {
            coordinator.fulfill("token-1") {
                operationCount.incrementAndGet()
                BillingResult.success(Unit)
            }
        }

        releaseOperation.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, operationCount.get())
        assertTrue(clearedTokens.isNotEmpty())
        assertTrue(clearedTokens.all { it == "token-1" })
    }

    @Test
    fun `caller cancellation does not cancel shared fulfillment and completed token is idempotent`() = runBlocking {
        val operationStarted = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()
        val operationCount = AtomicInteger()
        val coordinator = PurchaseFulfillmentCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onFulfilled = {},
        )

        val cancelledCaller = async {
            coordinator.fulfill("token-1") {
                operationCount.incrementAndGet()
                operationStarted.complete(Unit)
                releaseOperation.await()
                BillingResult.success(Unit)
            }
        }
        operationStarted.await()
        cancelledCaller.cancelAndJoin()
        releaseOperation.complete(Unit)

        val recoveredCaller = coordinator.fulfill("token-1") {
            operationCount.incrementAndGet()
            BillingResult.success(Unit)
        }

        assertTrue(recoveredCaller.isSuccess)
        assertEquals(1, operationCount.get())
    }

    @Test
    fun `failed fulfillment can be retried immediately`() = runBlocking {
        val operationCount = AtomicInteger()
        val coordinator = PurchaseFulfillmentCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onFulfilled = {},
        )

        val failed = coordinator.fulfill("token-1") {
            operationCount.incrementAndGet()
            com.zorv.core.billing.BillingResult.error(6, "failed")
        }
        val retried = coordinator.fulfill("token-1") {
            operationCount.incrementAndGet()
            BillingResult.success(Unit)
        }

        assertTrue(failed.isError)
        assertTrue(retried.isSuccess)
        assertEquals(2, operationCount.get())
    }
}
