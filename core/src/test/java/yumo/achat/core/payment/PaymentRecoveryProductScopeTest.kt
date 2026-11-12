package yumo.achat.core.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.*
import yumo.achat.core.network.AuthResponse

class PaymentRecoveryProductScopeTest {
    private class Store : PaymentStorage {
        var records = emptyList<PaymentRecord>()
        override suspend fun read() = records
        override suspend fun write(records: List<PaymentRecord>) { this.records = records.toList() }
    }
    private class Api : PaymentRepository {
        var recovered: RecoveredPaymentOrder? = null
        val statusQueries = mutableListOf<String>()
        override suspend fun recover(productId: String, session: Session) = recovered
        override suspend fun createOrder(productId: String, requestId: String, session: Session): PaymentBusinessOrder = error("factory creates")
        override suspend fun initialize(orderId: String, session: Session) = InitializedPayment(orderId, "official", "google_play", "sdk", PaymentSdkParams("sku-$orderId"))
        override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
            statusQueries += orderId
            return PaymentOrderStatus(orderId, "paid", "fulfilled")
        }
        override suspend fun close(orderId: String, session: Session): PaymentOrderStatus = error("unexpected close")
        override suspend fun event(orderId: String, event: PaymentClientEvent, session: Session) = Unit
    }
    private class Fixture {
        val store = Store(); val api = Api(); val sessions = SessionCoordinator(MemoryStorage())
        val createdProducts = mutableListOf<String>()
        val kinds = mutableMapOf("monthly" to PaymentProductKind.Membership, "coins" to PaymentProductKind.Recharge,
            "other-coins" to PaymentProductKind.Recharge)
        val events = mutableListOf<String>()
        val tracker = yumo.achat.core.analytics.RecordingTracker()
        suspend fun login() { sessions.saveLogin(AuthResponse("token", "refresh", "user")) }
        fun engine() = PaymentEngine(store, api, object : PaymentOrderFactory {
            override suspend fun productKind(productId: String, session: Session) = kinds[productId] ?: PaymentProductKind.Unknown
            override suspend fun create(productId: String, requestId: String, session: Session): PaymentBusinessOrder {
                createdProducts += productId
                return PaymentBusinessOrder("new-${createdProducts.size}", "user", "new-${createdProducts.size}")
            }
        }, sessions, PaymentEventSink { _, type, _, _, _ -> events += type }, PaymentClock { 1_000_000L },
            PaymentConfiguration("https://example.test/", "scope-test", 10, 600, 3000, 100, 3, 5000), tracker)
        fun remote(product: String) = RecoveredPaymentOrder("remote-order", product, "pending", "user", "remote-order",
            1.99, "USD", paymentMethod = "google_play", providerProductId = "sku-$product", fulfillmentStatus = "pending")
        fun polluted() = PaymentRecord("subscription-key", "user", "monthly", "store", orderId = "subscription-order",
            initialized = InitializedPayment("subscription-order", "official", "google_play", "sdk", PaymentSdkParams("monthly-sku")),
            stage = PaymentStage.OFFICIAL_LAUNCHED, obfuscatedAccountId = "user", obfuscatedProfileId = "subscription-order")
    }
    @Test fun remoteMembershipDoesNotHijackSelectedRecharge() = runBlocking {
        val f = Fixture(); f.login(); f.api.recovered = f.remote("monthly")
        val engine = f.engine(); engine.buy("coins", "store")
        assertEquals(listOf("coins"), f.createdProducts)
        assertEquals("coins", engine.state.value.record!!.productId)
        assertNotEquals("remote-order", engine.state.value.record!!.orderId)
    }
    @Test fun pollutedLaunchedSubscriptionIsQuarantinedAcrossRestartsWithoutLosingIdentity() = runBlocking {
        val f = Fixture(); f.login(); val saved = f.polluted(); f.store.records = listOf(saved)
        var engine = f.engine(); engine.restore()
        assertNull(engine.state.value.record)
        val isolated = f.store.records.single()
        assertEquals("membership", isolated.productType)
        assertEquals(saved.key, isolated.key)
        assertEquals(saved.orderId, isolated.orderId)
        assertEquals(saved.initialized, isolated.initialized)
        assertEquals(saved.obfuscatedProfileId, isolated.obfuscatedProfileId)
        engine = f.engine(); engine.restore()
        assertNull(engine.state.value.record)
        engine.buy("coins", "store")
        assertEquals(listOf("coins"), f.createdProducts)
        assertTrue(f.store.records.any { it.key == saved.key && it.productType == "membership" })
    }
    @Test fun staleRetryOrOfficialClaimCannotReactivateQuarantinedSubscription() = runBlocking {
        val f = Fixture(); f.login(); val old = f.polluted(); f.store.records = listOf(old)
        val engine = f.engine(); engine.restore(); engine.retry(old.key)
        assertNull(engine.claimOfficial(old.key))
        assertNull(engine.state.value.record)
        assertTrue(f.createdProducts.isEmpty())
        assertTrue(f.api.statusQueries.isEmpty())
    }
    @Test fun consumedOrFailedHistoricalMembershipCannotBecomeRechargeOnLateNotification() = runBlocking {
        for (stage in listOf(PaymentStage.OFFICIAL_CONSUMED, PaymentStage.FAILED)) {
            val f = Fixture(); f.login(); val old = f.polluted().copy(stage = stage)
            f.store.records = listOf(old)
            val engine = f.engine(); engine.restore(); engine.recharge(old.orderId)
            assertEquals("membership", f.store.records.single().productType)
            assertEquals(stage, f.store.records.single().stage)
            assertTrue(f.api.statusQueries.isEmpty())
            assertTrue(f.tracker.events.none { it.name == "purchase_success" })
        }
    }
    @Test fun anotherRealRechargeStillRecoversInsteadOfCreatingSecondCharge() = runBlocking {
        val f = Fixture(); f.login(); f.api.recovered = f.remote("other-coins")
        val engine = f.engine(); engine.buy("coins", "store")
        assertTrue(f.createdProducts.isEmpty())
        assertEquals("other-coins", engine.state.value.record!!.productId)
        assertEquals("remote-order", engine.state.value.record!!.orderId)
        assertTrue(engine.state.value.record!!.recoveryRequired)
    }
    @Test fun unknownRemoteProductBlocksNewOrder() = runBlocking {
        val f = Fixture(); f.login(); f.api.recovered = f.remote("unknown")
        val engine = f.engine(); engine.buy("coins", "store")
        assertTrue(f.createdProducts.isEmpty())
        assertTrue(engine.state.value.recoveryLookupFailed)
    }
    @Test fun unknownPersistedOrderIsRetainedAndBlocksCreation() = runBlocking {
        val f = Fixture(); f.login(); f.store.records = listOf(f.polluted().copy(productId = "unknown"))
        val engine = f.engine(); engine.restore(); engine.buy("coins", "store")
        assertTrue(f.createdProducts.isEmpty())
        assertEquals("subscription-order", f.store.records.single().orderId)
    }
    @Test fun subscriptionRechargeNotificationCannotFulfillConsumeOrReplaceRecharge() = runBlocking {
        val f = Fixture(); f.login(); val subscription = f.polluted(); f.store.records = listOf(subscription)
        val engine = f.engine(); engine.restore(); engine.buy("coins", "store")
        val activeKey = engine.state.value.record!!.key
        engine.recharge(subscription.orderId)
        assertEquals(activeKey, engine.state.value.record!!.key)
        assertFalse(f.api.statusQueries.contains(subscription.orderId))
        assertTrue(f.tracker.events.none { it.name == "purchase_success" })
        val isolated = f.store.records.single { it.key == subscription.key }
        assertEquals("membership", isolated.productType)
        assertNotEquals(PaymentStage.SUCCESS, isolated.stage)
        assertNotEquals(PaymentStage.OFFICIAL_CONSUMED, isolated.stage)
    }
}
