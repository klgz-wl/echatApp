package yumo.achat.core.payment

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PaymentServerRecoveryTest {
    private fun order(method: String = "", status: String = "pending") = RecoveredPaymentOrder(
        "original", "server-product", status, "user", "original", 7.25, "USD", method, "server-sku", "pending", "manual")

    @Test fun `fresh purchase recovers server snapshot and waits for explicit retry across restart`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.api.recovered = order()
        var engine = f.engine(); engine.buy("different", "main")
        assertEquals(0, f.orders)
        assertEquals(1, f.api.recoverCalls)
        assertEquals(listOf("different"), f.api.recoverProducts)
        assertTrue(f.api.initialized.isEmpty())
        assertFalse(engine.state.value.checkoutVisible)
        val saved = f.store.records.single()
        assertEquals("server-product", saved.productId)
        assertEquals(7.25, saved.quotePrice)
        assertEquals("USD", saved.quoteCurrency)
        assertEquals("server_snapshot", saved.quoteSource)
        assertTrue(saved.recoveryRequired)
        engine = f.engine(); engine.restore()
        engine.buy("different", "main")
        assertTrue(f.api.initialized.isEmpty())
        engine.retry(saved.key)
        assertEquals(listOf("original"), f.api.initialized)
        assertTrue(engine.state.value.checkoutVisible)
        assertEquals(0, f.orders)
    }

    @Test fun `review required blocks creation and exposes safe support issue`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        f.api.recoverFailure = PaymentFailure.Http(409, "PAYMENT_RECOVERY_REQUIRES_REVIEW")
        val engine = f.engine(); engine.buy("coins", "main")
        assertEquals("payment_review_required", engine.state.value.recoveryLookupError)
        assertTrue(engine.state.value.recoveryLookupFailed)
        assertTrue(f.store.records.isEmpty())
        assertEquals(0, f.orders)
        assertTrue(f.api.initialized.isEmpty())
    }

    @Test fun `lookup network and review failures retry lookup after restart without becoming lost create`() = runBlocking {
        for (failure in listOf(java.io.IOException(), PaymentFailure.Http(409, "PAYMENT_RECOVERY_REQUIRES_REVIEW"))) {
            val f = PaymentEngineTest.Fixture(); f.login(); f.api.recoverFailure = failure
            var engine = f.engine()
            repeat(2) { engine.buy("different", "main") }
            assertTrue(f.store.records.isEmpty())
            engine = f.engine(); engine.restore()
            engine.buy("different", "main")
            assertEquals(3, f.api.recoverCalls)
            assertEquals(0, f.orders)
            assertTrue(f.store.records.isEmpty())
            f.api.recoverFailure = null; f.api.recovered = order()
            engine.buy("different", "main")
            val original = f.store.records.single()
            assertEquals("server-product", original.productId)
            assertEquals(7.25, original.quotePrice)
            assertEquals("USD", original.quoteCurrency)
            assertTrue(original.recoveryRequired)
            assertFalse(engine.state.value.checkoutVisible)
            assertEquals(0, f.orders)
            engine = f.engine(); engine.restore()
            engine.retry(original.key)
            assertEquals(listOf("original"), f.api.initialized)
        }
    }

    @Test fun `lookup failure blocks fresh intent and retry performs lookup again`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.api.recoverFailure = java.io.IOException()
        val engine = f.engine(); engine.buy("coins", "main")
        assertEquals(0, f.orders)
        assertTrue(f.store.records.isEmpty())
        assertTrue(engine.state.value.recoveryLookupFailed)
        f.api.recoverFailure = null; engine.buy("coins", "main")
        assertEquals(2, f.api.recoverCalls)
        assertEquals(1, f.orders)
    }

    @Test fun `initialized google play recovery only queries original order`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.api.recovered = order("google_play")
        val engine = f.engine(); engine.buy("different", "main")
        val key = engine.state.value.record!!.key
        engine.retry(key); engine.retry(key)
        assertTrue(f.api.initialized.isEmpty())
        assertTrue(f.api.statusCalls > 0)
        assertEquals(PaymentStage.OFFICIAL_LAUNCHED, engine.state.value.record?.stage)
        assertNull(engine.claimOfficial(key))
        assertEquals(0, f.orders)
    }

    @Test fun `paid unfulfilled recovery only checks status even with no method`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.api.recovered = order(status = "paid")
        f.api.status = "paid"; f.api.fulfillment = "pending"
        val engine = f.engine(); engine.buy("different", "main")
        val key = engine.state.value.record!!.key
        engine.retry(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        assertTrue(f.api.initialized.isEmpty())
        assertFalse(engine.state.value.checkoutVisible)
        f.api.fulfillment = "fulfilled"; engine.poll(key)
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)
    }

    @Test fun `third party recovery retries initializer for same order`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.api.recovered = order("payu_web")
        val engine = f.engine(); engine.buy("different", "main")
        engine.retry(engine.state.value.record!!.key)
        assertEquals(listOf("original"), f.api.initialized)
        assertEquals("server-product", engine.state.value.record?.productId)
        assertEquals(0, f.orders)
    }

    @Test fun `lost create after restart replays original request without repeating lookup`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); f.createFailure = true
        var engine = f.engine(); engine.buy("coins", "main")
        val requestId = f.store.records.single().key
        f.api.recoverFailure = java.io.IOException()
        engine = f.engine(); engine.restore(); f.createFailure = false
        engine.buy("other", "main")
        assertEquals(1, f.api.recoverCalls)
        assertEquals(listOf(requestId, requestId), f.requestKeys)
        assertEquals(1, f.orders)
        assertEquals("coins", engine.state.value.record?.productId)
    }

    @Test fun `delayed lookup from old session cannot persist or create an order`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : PaymentRepository by f.api {
            override suspend fun recover(productId: String, session: yumo.achat.core.auth.Session): RecoveredPaymentOrder? {
                entered.complete(Unit); release.await(); return order()
            }
        }
        val engine = f.engine(api)
        val buy = launch { engine.buy("coins", "main") }; entered.await()
        f.login("other"); release.complete(Unit); buy.join()
        assertTrue(f.store.records.isEmpty())
        assertNull(engine.state.value.record)
        assertEquals(0, f.orders)
    }
}
