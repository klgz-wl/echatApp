package yumo.achat.core.payment

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.Session

class PaymentFinancialRecoveryRegressionTest {
    @Test fun `trusted paid official state survives late SDK cancellation or failure`() = runBlocking {
        for (cancelled in listOf(true, false)) {
            val f = PaymentEngineTest.Fixture(); f.login(); f.api.official = true
            val engine = f.engine(); engine.buy("coins", "manual")
            val key = engine.state.value.record!!.key
            engine.claimOfficial(key)
            f.api.status = "paid"; engine.poll(key)
            engine.officialResult(key, cancelledOrUnavailable = cancelled, failed = !cancelled)
            assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        }
    }
    @Test fun `paid pending survives close expiry restart and network errors`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        var engine = f.engine(); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key
        engine.opened(key)
        f.api.status = "paid"; f.api.fulfillment = "pending"
        engine.poll(key)
        engine.close(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        assertFalse(engine.state.value.checkoutVisible)
        f.now = engine.state.value.record!!.checkoutDeadline!!
        engine.expireCheckout(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        engine = f.engine(); engine.restore()
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        f.api.queryFailure = true; engine.poll(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
    }

    @Test fun `late paid pending wins over dismissal during suspended query`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : PaymentRepository by f.api {
            override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
                entered.complete(Unit); release.await()
                return PaymentOrderStatus(orderId, "paid", "pending")
            }
        }
        val engine = f.engine(api); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key
        val poll = launch { engine.poll(key) }; entered.await()
        val close = launch(start = CoroutineStart.UNDISPATCHED) { engine.close(key) }
        release.complete(Unit); poll.join(); close.join()
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)
        assertFalse(engine.state.value.checkoutVisible)
    }

    @Test fun `external browser paid pending polls even without opened timestamp`() {
        val record = PaymentRecord("key", "user", "coins", "restored", orderId = "order",
            stage = PaymentStage.AWAITING_FULFILLMENT,
            initialized = InitializedPayment("order", "third_party", "payu", "external_browser",
                paymentUrl = "https://checkout.example", expiresAt = "2030-01-01T00:00:00Z"))
        assertTrue(shouldAutoPollPayment(record))
    }

    @Test fun `canonical failure and retry codes survive allowlist`() {
        for (reason in listOf("payment_expired", "payment_failed")) {
            val status = PaymentOrderStatus("order", "expired", failureReason = reason, retryAction = "check_payment")
            assertEquals(reason, status.safeFailureReason)
            assertEquals("check_payment", status.safeRetryAction)
        }
    }
}
