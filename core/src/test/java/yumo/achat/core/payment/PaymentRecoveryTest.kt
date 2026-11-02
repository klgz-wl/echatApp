package yumo.achat.core.payment

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.Session

class PaymentRecoveryTest {
    @Test fun `explicit 409 product unavailable releases no order intent across restart`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val attempts = mutableListOf<Pair<String, String>>()
        var unavailable = true
        val factory = PaymentOrderFactory { product, key, _ ->
            attempts += product to key
            if (unavailable) throw PaymentFailure.Http(409, "PAYMENT_PRODUCT_UNAVAILABLE")
            PaymentBusinessOrder("available-order")
        }
        fun engine() = PaymentEngine(f.store, f.api, factory, f.sessions,
            PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { f.now }, f.config)
        var engine = engine(); engine.buy("sold-out", "manual")
        val rejected = engine.state.value.record!!
        assertEquals(PaymentStage.FAILED, rejected.stage)
        assertEquals("product_unavailable", rejected.error)
        assertNull(rejected.orderId)
        assertFalse(rejected.unresolved)
        engine = engine(); engine.restore(); assertNull(engine.state.value.record)
        unavailable = false; engine.buy("available", "manual")
        assertEquals("available", engine.state.value.record?.productId)
        assertNotEquals(rejected.key, engine.state.value.record?.key)
        assertEquals(listOf("sold-out", "available"), attempts.map { it.first })
    }

    @Test fun `conflict unknown status and forbidden create errors preserve original request`() = runBlocking {
        for ((status, reason) in listOf(409 to "REQUEST_ID_CONFLICT", 409 to null,
            409 to "PRODUCT_UNAVAILABLE", 500 to "PAYMENT_PRODUCT_UNAVAILABLE", 403 to "payment order forbidden")) {
            val f = PaymentEngineTest.Fixture(); f.login()
            val keys = mutableListOf<String>()
            val factory = PaymentOrderFactory { _, key, _ ->
                keys += key; throw PaymentFailure.Http(status, reason)
            }
            val engine = PaymentEngine(f.store, f.api, factory, f.sessions,
                PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { f.now }, f.config)
            engine.buy("original", "manual"); val key = engine.state.value.record!!.key
            assertEquals(PaymentStage.UNCERTAIN, engine.state.value.record?.stage)
            assertTrue(engine.state.value.record!!.unresolved)
            engine.buy("other", "manual")
            assertEquals(listOf(key, key), keys)
            assertEquals("original", engine.state.value.record?.productId)
        }
    }

    @Test fun `slow opened persistence cannot republish visible checkout after expiry`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var pause = false
        val storage = object : PaymentStorage by f.store {
            override suspend fun write(records: List<PaymentRecord>) {
                if (pause) { entered.complete(Unit); release.await() }
                f.store.write(records)
            }
        }
        val engine = PaymentEngine(storage, f.api, PaymentOrderFactory { _, _, _ -> PaymentBusinessOrder("order-1") },
            f.sessions, PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { f.now }, f.config)
        engine.buy("coins", "manual"); val key = engine.state.value.record!!.key
        pause = true
        val visibilityAfterWrite = async { engine.opened(key); engine.state.value.checkoutVisible }
        entered.await(); f.now = engine.state.value.record!!.checkoutDeadline!!
        val expiration = launch(start = CoroutineStart.UNDISPATCHED) { engine.expireCheckout(key) }
        assertFalse(engine.state.value.checkoutVisible)
        release.complete(Unit)
        assertFalse(visibilityAfterWrite.await())
        expiration.join()
    }

    @Test fun `forbidden status releases inaccessible order without restoring checkout`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val api = object : PaymentRepository by f.api {
            override suspend fun status(orderId: String, session: Session): PaymentOrderStatus =
                throw PaymentFailure.Http(403, "payment order forbidden")
        }
        var engine = f.engine(api); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key; engine.opened(key); engine.poll(key)
        assertEquals(PaymentStage.ACCESS_DENIED, engine.state.value.record?.stage)
        assertFalse(engine.state.value.checkoutVisible)
        assertFalse(engine.state.value.record!!.unresolved)
        engine = f.engine(api); engine.restore(); assertNull(engine.state.value.record)
    }

    @Test fun `rapid concurrent purchases share one durable request`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); val engine = f.engine()
        coroutineScope { repeat(100) { launch { engine.buy("coins", "manual") } } }
        assertEquals(1, f.orders)
        assertEquals(1, f.requestKeys.distinct().size)
        assertEquals(f.requestKeys.single(), f.store.records.single().key)
    }

    @Test fun `close persists before server close and terminal unpaid permits next intent`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        var closeCalls = 0
        val api = object : PaymentRepository by f.api {
            override suspend fun close(orderId: String, session: Session): PaymentOrderStatus {
                closeCalls++
                assertEquals(PaymentStage.CLOSED, f.store.records.single().stage)
                return PaymentOrderStatus(orderId, "cancelled")
            }
        }
        val engine = f.engine(api); engine.buy("coins", "manual")
        engine.close(engine.state.value.record!!.key)
        assertEquals(1, closeCalls)
        assertEquals(PaymentStage.FAILED, engine.state.value.record?.stage)
        engine.buy("other", "manual")
        assertEquals(2, f.orders)
        assertEquals("other", engine.state.value.record?.productId)
    }

    @Test fun `expiry hides checkout while status call is suspended and late pending cannot reopen`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : PaymentRepository by f.api {
            override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
                entered.complete(Unit); release.await()
                return PaymentOrderStatus(orderId, "pending")
            }
        }
        val engine = f.engine(api); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key; engine.opened(key)
        val query = launch { engine.poll(key) }; entered.await()
        f.now = engine.state.value.record!!.checkoutDeadline!!
        val expiration = launch(start = CoroutineStart.UNDISPATCHED) { engine.expireCheckout(key) }
        assertFalse(engine.state.value.checkoutVisible)
        assertEquals(PaymentStage.TIMED_OUT, engine.state.value.record?.stage)
        release.complete(Unit); query.join(); expiration.join()
        assertFalse(engine.state.value.checkoutVisible)
        assertEquals(PaymentStage.TIMED_OUT, f.store.records.single().stage)
        assertTrue(f.store.records.single().unresolved)
    }

    @Test fun `explicit close hides checkout while existing query is suspended`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : PaymentRepository by f.api {
            override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
                entered.complete(Unit); release.await()
                return PaymentOrderStatus(orderId, "pending")
            }
        }
        val engine = f.engine(api); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key; engine.opened(key)
        val query = launch { engine.poll(key) }; entered.await()
        val closing = launch(start = CoroutineStart.UNDISPATCHED) { engine.close(key) }
        assertFalse(engine.state.value.checkoutVisible)
        release.complete(Unit); query.join(); closing.join()
        assertEquals(PaymentStage.CLOSED, engine.state.value.record?.stage)
        assertFalse(engine.state.value.checkoutVisible)
    }

    @Test fun `local timeout survives restart and trusted late paid fulfills once`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); var engine = f.engine()
        engine.buy("coins", "manual"); val key = engine.state.value.record!!.key
        f.now = engine.state.value.record!!.checkoutDeadline!!
        engine.expireCheckout(key)
        assertEquals("timeout", engine.state.value.record?.error)
        engine = f.engine(); engine.restore()
        assertEquals(key, engine.state.value.record?.key)
        f.api.status = "paid"; f.api.fulfillment = "fulfilled"
        engine.recharge("order-1"); engine.recharge("order-1")
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)
        assertEquals(1, f.tracker.events.count { it.name == "purchase_success" })
        assertEquals(1, f.orders)
    }

    @Test fun `page reload clears only recoverable network error`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login(); val engine = f.engine()
        engine.buy("coins", "manual"); val key = engine.state.value.record!!.key; engine.opened(key)
        engine.pageEvent(key, "page_load_error", PaymentClientEventDetails(errorMessage = "sensitive provider text"))
        assertEquals("network_error", engine.state.value.record?.error)
        assertTrue(engine.state.value.checkoutVisible)
        engine.pageEvent(key, "page_loaded")
        assertNull(engine.state.value.record?.error)
        assertTrue(engine.state.value.checkoutVisible)
    }

    @Test fun `card guidance accepts only known provider independent codes`() = runBlocking {
        val f = PaymentEngineTest.Fixture(); f.login()
        var reason = "card_declined"; var action = "change_card"
        val api = object : PaymentRepository by f.api {
            override suspend fun status(orderId: String, session: Session) =
                PaymentOrderStatus(orderId, "pending", failureReason = reason, retryAction = action)
        }
        val engine = f.engine(api); engine.buy("coins", "manual")
        val key = engine.state.value.record!!.key; engine.opened(key)
        for (code in listOf("card_declined", "card_expired", "card_invalid", "insufficient_funds", "timeout")) {
            reason = code; engine.poll(key)
            assertEquals(code, engine.state.value.record?.error)
            assertEquals("change_card", engine.state.value.record?.retryAction)
            assertTrue(engine.state.value.record!!.unresolved)
        }
        reason = "PAN or raw provider error"; action = "https://evil.example/card"
        engine.poll(key)
        assertNull(engine.state.value.record?.error)
        assertNull(engine.state.value.record?.retryAction)
    }
}
