package yumo.achat.core.billing

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRecoveryAndLedgerTest {
    @Test fun recoveryRetriesFailuresAndAcceptsLaterForegroundAfterExhaustion() = runBlocking {
        var attempts = 0
        val worker = SubscriptionRecoveryWorker(this, 1) { attempts++; false }
        worker.request().join()
        assertEquals(3, attempts)
        worker.request().join()
        assertEquals(6, attempts)
    }
    @Test fun concurrentRecoverySignalsCoalesceAndCompletionDoesNotSuppressLaterSignals() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var attempts = 0
        val worker = SubscriptionRecoveryWorker(this, 1) { attempts++; entered.complete(Unit); finish.await(); true }
        val first = worker.request(); entered.await()
        assertSame(first, worker.request())
        finish.complete(Unit); first.join()
        worker.request().join()
        assertEquals(2, attempts)
    }
    @Test fun snapshotSurvivesRestartKeepsOriginalQuoteAndClaimsOnlyOnce() {
        val disk = mutableMapOf<String, String>()
        fun ledger() = SubscriptionPurchaseLedger(Json, { disk[it] }, { key, value -> disk[key] = value })
        val original = SubscriptionPurchaseSnapshot("purchase", "owner", "order", "sku", "profile_page", false, 1230000, "EUR")
        ledger().prepare(original)
        ledger().prepare(original.copy(trigger = "video_templates", priceMicros = 9990000))
        assertEquals(original.copy(playOrderId = "GPA.1"), ledger().claim("owner", "order", "token", "GPA.1"))
        assertNull(ledger().claim("owner", "order", "token", "GPA.1"))
        assertNull(ledger().claim("another-owner", "order", "token", "GPA.1"))
    }
    @Test fun purchasedReportsBeforeAckAndAckFailureDoesNotSuppressStatistics() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(handlePurchasedSubscription(PurchaseState.PURCHASED,
            { calls += "report" }, { calls += "ack"; error("offline") }))
        assertEquals(listOf("report", "ack"), calls)
        calls.clear()
        assertTrue(handlePurchasedSubscription(PurchaseState.PURCHASED,
            { calls += "report"; error("analytics") }, { calls += "ack"; true }))
        assertEquals(listOf("report", "ack"), calls)
    }
    @Test fun duplicatePurchasedCallbacksAfterAckFailureKeepOneOriginalRevenueEvent() = runBlocking {
        val disk = mutableMapOf<String, String>()
        val ledger = SubscriptionPurchaseLedger(Json, { disk[it] }, { key, value -> disk[key] = value })
        ledger.prepare(SubscriptionPurchaseSnapshot("purchase", "owner", "order", "sku", "profile_page", false, 1230000, "EUR"))
        val events = mutableListOf<Map<String, Any>>()
        repeat(2) {
            handlePurchasedSubscription(PurchaseState.PURCHASED, {
                ledger.claim("owner", "order", "token", "GPA.1")?.let { snapshot ->
                    events += subscriptionPurchaseProperties(snapshot, "server-product")
                }
            }, { false })
        }
        assertEquals(1, events.size)
        assertEquals(1.23, events.single()["af_price"])
        assertEquals("EUR", events.single()["af_currency"])
        assertEquals("profile_page", events.single()["entry_source"])
        assertFalse(events.single().values.contains("token"))
    }
    @Test fun unknownQuoteOmitsAllRevenueFieldsAndKnownZeroStaysZero() {
        val original = SubscriptionPurchaseSnapshot("purchase", "owner", "order", "sku", null, null, 0, "EUR")
        assertEquals(0.0, subscriptionPurchaseProperties(original, "product")["af_price"])
        for (snapshot in listOf(original.copy(priceMicros = null), original.copy(priceMicros = -1),
            original.copy(currency = null), original.copy(currency = "ZZZ"))) {
            val values = subscriptionPurchaseProperties(snapshot, "product")
            assertFalse(values.containsKey("af_price")); assertFalse(values.containsKey("af_currency"))
            assertFalse(values.containsKey("is_vip"))
        }
    }
    @Test fun pendingAndUnknownNeverReportOrAcknowledge() = runBlocking {
        for (state in listOf(PurchaseState.PENDING, PurchaseState.UNSPECIFIED)) {
            assertFalse(handlePurchasedSubscription(state, { fail() }, { fail(); true }))
        }
    }
}
