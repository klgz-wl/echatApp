package com.vexora.core.billing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumablePurchaseOrchestratorTest {
    @Test fun `任一步失败都不执行后续订单或消费`() = runBlocking {
        for (stop in ConsumablePurchaseStage.entries.indices) {
            val calls = mutableListOf<Int>()
            fun <T> step(index: Int, value: T): PurchaseStepResult<T> {
                calls += index
                return if (index == stop) PurchaseStepResult.Failure() else PurchaseStepResult.Success(value)
            }
            val result = ConsumablePurchaseOrchestrator().purchase(
                { step(0, Unit) }, { step(1, "product") }, { step(2, "order") },
                { step(3, "token") }, { step(4, Unit) })
            assertEquals((0..stop).toList(), calls)
            assertEquals(ConsumablePurchaseStage.entries[stop], (result as ConsumablePurchaseResult.Failure).stage)
        }
    }
    @Test fun `取消保持原始返回码且不消费`() = runBlocking {
        var consumed = false
        val result = ConsumablePurchaseOrchestrator().purchase(
            { PurchaseStepResult.Success(Unit) }, { PurchaseStepResult.Success("product") },
            { PurchaseStepResult.Success("order") }, { PurchaseStepResult.Failure(code = BillingResponseCode.CANCELLED) },
            { consumed = true; PurchaseStepResult.Success(Unit) })
        assertEquals(BillingResponseCode.CANCELLED, (result as ConsumablePurchaseResult.Failure).code)
        assertEquals(false, consumed)
    }
    @Test fun `异常之后释放购买占用`() = runBlocking {
        val orchestrator = ConsumablePurchaseOrchestrator()
        try {
            orchestrator.purchase({ throw java.io.IOException() }, { PurchaseStepResult.Success("product") },
                { PurchaseStepResult.Success("order") }, { PurchaseStepResult.Success("token") }, { PurchaseStepResult.Success(Unit) })
        } catch (_: java.io.IOException) { }
        val retried = orchestrator.purchase({ PurchaseStepResult.Success(Unit) }, { PurchaseStepResult.Success("product") },
            { PurchaseStepResult.Success("order") }, { PurchaseStepResult.Success("token") }, { PurchaseStepResult.Success(Unit) })
        assertTrue(retried is ConsumablePurchaseResult.Success)
    }

    @Test
    fun `purchase executes steps in safe order`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ConsumablePurchaseOrchestrator().purchase(
            initializeBilling = { calls += "initialize"; PurchaseStepResult.Success(Unit) },
            queryStoreProduct = { calls += "query"; PurchaseStepResult.Success("store-product") },
            createOrder = { calls += "create-order"; PurchaseStepResult.Success("order-id") },
            launchPurchase = { calls += "launch-$it"; PurchaseStepResult.Success("purchase-token") },
            consumePurchase = { calls += "consume-$it"; PurchaseStepResult.Success(Unit) },
        )

        assertEquals(
            listOf("initialize", "query", "create-order", "launch-order-id", "consume-purchase-token"),
            calls,
        )
        assertEquals(ConsumablePurchaseResult.Success("store-product"), result)
    }

    @Test
    fun `consume failure is not reported as purchase success`() = runBlocking {
        val result = ConsumablePurchaseOrchestrator().purchase(
            initializeBilling = { PurchaseStepResult.Success(Unit) },
            queryStoreProduct = { PurchaseStepResult.Success("store-product") },
            createOrder = { PurchaseStepResult.Success("order-id") },
            launchPurchase = { PurchaseStepResult.Success("purchase-token") },
            consumePurchase = { PurchaseStepResult.Failure("consume failed") },
        )

        assertEquals(
            ConsumablePurchaseResult.Failure(
                stage = ConsumablePurchaseStage.CONSUMPTION,
                message = "consume failed",
            ),
            result,
        )
    }

    @Test
    fun `pending purchase returns immediately without consuming`() = runBlocking {
        var consumeCalls = 0
        val result = ConsumablePurchaseOrchestrator().purchase(
            initializeBilling = { PurchaseStepResult.Success(Unit) },
            queryStoreProduct = { PurchaseStepResult.Success("store-product") },
            createOrder = { PurchaseStepResult.Success("order-id") },
            launchPurchase = { PurchaseStepResult.Pending("pending-token") },
            consumePurchase = {
                consumeCalls++
                PurchaseStepResult.Success(Unit)
            },
        )

        assertEquals(ConsumablePurchaseResult.Pending("store-product"), result)
        assertEquals(0, consumeCalls)
    }

    @Test
    fun `concurrent purchase is rejected while first purchase is active`() = runBlocking {
        val firstPurchaseStarted = CompletableDeferred<Unit>()
        val releaseFirstPurchase = CompletableDeferred<Unit>()
        val orchestrator = ConsumablePurchaseOrchestrator()

        val first = async {
            orchestrator.purchase(
                initializeBilling = {
                    firstPurchaseStarted.complete(Unit)
                    releaseFirstPurchase.await()
                    PurchaseStepResult.Success(Unit)
                },
                queryStoreProduct = { PurchaseStepResult.Success("store-product") },
                createOrder = { PurchaseStepResult.Success("order-id") },
                launchPurchase = { PurchaseStepResult.Success("purchase-token") },
                consumePurchase = { PurchaseStepResult.Success(Unit) },
            )
        }
        firstPurchaseStarted.await()

        val second = orchestrator.purchase(
            initializeBilling = { PurchaseStepResult.Success(Unit) },
            queryStoreProduct = { PurchaseStepResult.Success("other-product") },
            createOrder = { PurchaseStepResult.Success("other-order") },
            launchPurchase = { PurchaseStepResult.Success("other-token") },
            consumePurchase = { PurchaseStepResult.Success(Unit) },
        )

        assertTrue(second is ConsumablePurchaseResult.InProgress)
        releaseFirstPurchase.complete(Unit)
        assertTrue(first.await() is ConsumablePurchaseResult.Success)
    }
}
