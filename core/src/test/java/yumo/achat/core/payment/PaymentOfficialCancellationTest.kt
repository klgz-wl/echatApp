package yumo.achat.core.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.*
import yumo.achat.core.network.AuthResponse

class PaymentOfficialCancellationTest {
    private class Store : PaymentStorage {
        var records = emptyList<PaymentRecord>()
        override suspend fun read() = records
        override suspend fun write(records: List<PaymentRecord>) { this.records = records.toList() }
    }
    private class Api : PaymentRepository {
        var recovery: RecoveredPaymentOrder? = null
        val statuses = mutableMapOf<String, PaymentOrderStatus>()
        override suspend fun recover(productId: String, session: Session) = recovery
        override suspend fun createOrder(productId: String, requestId: String, session: Session): PaymentBusinessOrder = error("factory owns create")
        override suspend fun close(orderId: String, session: Session): PaymentOrderStatus = error("official close is not allowed")
        override suspend fun initialize(orderId: String, session: Session) = InitializedPayment(orderId, "official", "google_play", "sdk", PaymentSdkParams("sku-$orderId"))
        override suspend fun status(orderId: String, session: Session) = statuses[orderId] ?: PaymentOrderStatus(orderId, "pending")
        override suspend fun event(orderId: String, event: PaymentClientEvent, session: Session) = Unit
    }
    private class Fixture {
        val store = Store(); val api = Api(); val sessions = SessionCoordinator(MemoryStorage())
        val creates = mutableListOf<Pair<String, String>>()
        suspend fun login() { sessions.saveLogin(AuthResponse("token", "refresh", "user")) }
        fun engine() = PaymentEngine(store, api, PaymentOrderFactory { product, key, session ->
            creates += product to key
            PaymentBusinessOrder("order-${creates.size}", session.userId, "order-${creates.size}")
        }, sessions, PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { 1_000_000L },
            PaymentConfiguration("https://example.test/", "test", 10, 600, 3000, 100, 3, 5000))
        suspend fun launched(engine: PaymentEngine): PaymentRecord {
            engine.buy("coins-100", "store")
            val key = engine.state.value.record!!.key
            return engine.claimOfficial(key)!!
        }
    }
    @Test fun explicitPlayCancellationAllowsDifferentProductWithNewOrderAndKey() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = true)
        val cancelled = f.store.records.single { it.key == old.key }
        assertEquals(PaymentStage.CLOSED, cancelled.stage)
        assertTrue(cancelled.officialCheckoutReleased)
        assertFalse(cancelled.unresolved)
        engine.buy("coins-500", "store")
        val next = engine.state.value.record!!
        assertEquals("coins-500", next.productId)
        assertNotEquals(old.key, next.key)
        assertNotEquals(old.orderId, next.orderId)
        assertEquals(2, f.creates.size)
    }
    @Test fun exactReleasedServerRecoveryCannotHijackNewSelectionAndRestartDoesNotRestoreIt() = runBlocking {
        val f = Fixture(); f.login(); var engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = true)
        engine = f.engine(); engine.restore()
        assertNull(engine.state.value.record)
        f.api.recovery = RecoveredPaymentOrder(old.orderId!!, old.productId, "pending", "user", old.orderId,
            1.99, "USD", paymentMethod = "google_play", providerProductId = "old-sku", fulfillmentStatus = "pending")
        engine.buy("coins-500", "store")
        assertEquals("coins-500", engine.state.value.record!!.productId)
        assertNotEquals(old.orderId, engine.state.value.record!!.orderId)
        assertEquals(2, f.creates.size)
    }
    @Test fun latePaidServerRecoveryIsNotIgnoredEvenWhenPreviouslyCancelledLocally() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = true)
        f.api.recovery = RecoveredPaymentOrder(old.orderId!!, old.productId, "paid", "user", old.orderId,
            1.99, "USD", paymentMethod = "google_play", providerProductId = "old-sku", fulfillmentStatus = "pending")
        engine.buy("coins-500", "store")
        assertEquals(1, f.creates.size)
        assertEquals(old.orderId, engine.state.value.record!!.orderId)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record!!.stage)
    }
    @Test fun unavailableWithoutExplicitCancelRemainsBlocking() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = false)
        assertTrue(f.store.records.single().unresolved)
        engine.buy("coins-500", "store")
        assertEquals(old.key, engine.state.value.record!!.key)
        assertEquals(1, f.creates.size)
    }
    @Test fun legacyClosedReleaseRequiresSuccessfulEmptyPlayQueryAndPendingBackend() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = false)
        engine.releaseClosedOfficial(old.key, hasStorePurchase = false, querySucceeded = false)
        assertTrue(f.store.records.single().unresolved)
        engine.releaseClosedOfficial(old.key, hasStorePurchase = true, querySucceeded = true)
        assertTrue(f.store.records.single().unresolved)
        engine.releaseClosedOfficial(old.key, hasStorePurchase = false, querySucceeded = true)
        assertFalse(f.store.records.single().unresolved)
    }
    @Test fun paidUnfulfilledCannotBeReleasedByLegacyEmptyPlayEvidence() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = false)
        f.api.statuses[old.orderId!!] = PaymentOrderStatus(old.orderId, "paid", "pending")
        engine.releaseClosedOfficial(old.key, hasStorePurchase = false, querySucceeded = true)
        assertTrue(f.store.records.single().unresolved)
        engine.buy("coins-500", "store")
        assertEquals(1, f.creates.size)
        assertEquals(old.key, engine.state.value.record!!.key)
    }
    @Test fun lateOldOrderFulfillmentDoesNotReplaceNewActivePurchase() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine(); val old = f.launched(engine)
        engine.officialResult(old.key, cancelledOrUnavailable = true, userCancelled = true)
        engine.buy("coins-500", "store")
        val newKey = engine.state.value.record!!.key
        f.api.statuses[old.orderId!!] = PaymentOrderStatus(old.orderId, "paid", "fulfilled")
        engine.recharge(old.orderId)
        assertEquals(newKey, engine.state.value.record!!.key)
        assertEquals("coins-500", engine.state.value.record!!.productId)
        assertEquals(PaymentStage.SUCCESS, f.store.records.single { it.key == old.key }.stage)
    }
}
