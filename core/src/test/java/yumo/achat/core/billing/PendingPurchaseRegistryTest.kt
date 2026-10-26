package yumo.achat.core.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import yumo.achat.core.analytics.RecordingTracker
import org.junit.Test

class PendingPurchaseRegistryTest {
    @Test fun `recovered purchase uses original Play amount and currency with original success identity`() {
        val saved = PendingPurchaseRecord("sku", "order", BillingProductType.IN_APP, "token",
            ownerUserId = "user", businessOrderId = "order", priceAmountMicros = 2990000,
            priceCurrencyCode = "EUR")
        val registry = PendingPurchaseRegistry()
        registry.restore(listOf(saved))
        val tracker = RecordingTracker()
        tracker.reportRecoveredPurchase(registry.findMatching(emptyList(), null, "token")!!, "user", "order")
        tracker.reportRecoveredPurchase(registry.findMatching(emptyList(), null, "token")!!, "user", "order")
        assertEquals(1, tracker.events.size)
        assertEquals(2.99, tracker.events.single().values["af_revenue"])
        assertEquals("EUR", tracker.events.single().values["af_currency"])
        assertEquals("play:order:purchase_success", tracker.events.single().once)
        val historical = RecordingTracker()
        historical.reportRecoveredPurchase(saved.copy(priceAmountMicros = null), "user", "old-order")
        assertFalse(historical.events.single().values.containsKey("af_revenue"))
    }
    @Test
    fun `restored transaction matches purchased callback by order after process restart`() {
        val registry = PendingPurchaseRegistry()
        val record = PendingPurchaseRecord(
            productId = "coins_100",
            orderId = "backend-order-1",
            productType = BillingProductType.IN_APP,
            purchaseToken = null,
            userId = "obfuscated-account",
            ownerUserId = "user-1",
            businessOrderId = "business-order-1",
            priceAmountMicros = 2990000,
            priceCurrencyCode = "EUR",
        )
        registry.restore(listOf(record))

        assertEquals(
            record,
            registry.findMatching(
                productIds = listOf("coins_100"),
                orderId = "backend-order-1",
                purchaseToken = "play-token",
            ),
        )
    }

    @Test
    fun `same sku cannot match ambiguously without token or order`() {
        val registry = PendingPurchaseRegistry()
        registry.register(PendingPurchaseRecord("coins_100", "order-1", BillingProductType.IN_APP, null))
        registry.register(PendingPurchaseRecord("coins_100", "order-2", BillingProductType.IN_APP, null))

        assertNull(registry.findMatching(listOf("coins_100"), null, null))
    }

    @Test
    fun `purchase token takes precedence and handled transaction is removed`() {
        val registry = PendingPurchaseRegistry()
        val first = PendingPurchaseRecord("coins_100", "order-1", BillingProductType.IN_APP, "token-1")
        val second = PendingPurchaseRecord("coins_100", "order-2", BillingProductType.IN_APP, "token-2")
        registry.register(first)
        registry.register(second)

        assertEquals(second, registry.findMatching(listOf("coins_100"), "order-1", "token-2"))
        registry.remove(second)
        assertNull(registry.findMatching(emptyList(), null, "token-2"))
    }

    @Test
    fun `owner user is retained when token is attached to persisted order`() {
        val registry = PendingPurchaseRegistry()
        val pending = PendingPurchaseRecord(
            productId = "coins_100",
            orderId = "order-1",
            productType = BillingProductType.IN_APP,
            purchaseToken = null,
            userId = "obfuscated-account",
            ownerUserId = "user-1",
            businessOrderId = "business-order-1",
            priceAmountMicros = 0,
            priceCurrencyCode = "JPY",
        )
        registry.restore(listOf(pending))

        val upgraded = registry.attachPurchaseToken(
            productIds = listOf("coins_100"),
            orderId = "order-1",
            purchaseToken = "token-1",
        )

        assertEquals("token-1", upgraded?.purchaseToken)
        assertEquals("user-1", upgraded?.ownerUserId)
        assertEquals("business-order-1", upgraded?.businessOrderId)
        assertEquals(0L, upgraded?.priceAmountMicros)
        assertEquals("JPY", upgraded?.priceCurrencyCode)
        assertEquals(
            "user-1",
            registry.findMatching(emptyList(), null, "token-1")?.ownerUserId,
        )
        registry.remove(upgraded!!)
        assertNull(registry.findMatching(emptyList(), null, "token-1"))
    }
}
