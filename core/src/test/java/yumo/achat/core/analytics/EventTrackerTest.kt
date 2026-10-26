package yumo.achat.core.analytics

import org.junit.Assert.*
import org.junit.Test

class EventTrackerTest {
    @Test fun `四端成功事件使用统一业务前缀`() {
        assertEquals(listOf("f_purchase_success", "a_purchase_success", "s_purchase_success", "z_purchase_success"),
            AnalyticsPlatform.entries.map { it.eventName("purchase_success") })
    }

    @Test fun `布尔数值规范一致且拒绝非有限金额和复杂对象`() {
        val result = analyticsParameters(mapOf("is_success" to true, "count" to 3, "amount" to 1.5, "invalid" to Double.NaN,
            "nested" to mapOf("token" to "secret"), "bad-key" to "bad", "description" to "x".repeat(120)))
        assertEquals(1L, result["is_success"]); assertEquals(3L, result["count"]); assertEquals(1.5, result["amount"])
        assertFalse(result.containsKey("invalid")); assertFalse(result.containsKey("nested")); assertFalse(result.containsKey("bad-key"))
        assertEquals(100, (result["description"] as String).length)
    }

    @Test fun `Play 成功沿用商品金额币种和完整订单参数`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "google_play", "LEGACY", "store", "coins_100", "order-1",
            "play_consumed", amount = 4.99, currency = "eur") + mapOf("af_content_id" to "play.sku.100")
        tracker.purchaseSuccess(values, "user", "play:order-1:purchase_success")
        val event = tracker.events.single()
        assertEquals("purchase_success", event.name)
        assertEquals("play:order-1:purchase_success", event.once)
        assertEquals("user", event.user)
        assertEquals("success", event.values["status"])
        assertEquals("true", event.values["af_success"])
        assertEquals("order-1", event.values["af_order_id"])
        assertEquals("play.sku.100", event.values["af_content_id"])
        assertEquals("recharge", event.values["af_content_type"])
        assertEquals("store", event.values["trigger"])
        assertEquals(4.99, event.values["af_price"])
        assertEquals(4.99, event.values["af_revenue"])
        assertEquals("EUR", event.values["af_currency"])
    }

    @Test fun `第三方仅有效美元订单报价计收入并保留来源`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "channel", "SERVICE", "generation", "coins", "order",
            "fulfilled") + paymentPriceProperties(2.99, "USD", "catalog_quote")
        tracker.purchaseSuccess(values, "user")
        val event = tracker.events.single().values
        assertEquals(2.99, event["af_price"])
        assertEquals(2.99, event["af_revenue"])
        assertEquals("USD", event["af_currency"])
        assertEquals("catalog_quote", event["price_source"])
        val nonUsd = RecordingTracker()
        nonUsd.purchaseSuccess(values + ("af_currency" to "EUR"), "user")
        assertFalse(nonUsd.events.single().values.containsKey("af_revenue"))
    }

    @Test fun `缺失无效金额不虚构收入而已知零金额有效`() {
        for (amount in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val tracker = RecordingTracker()
            tracker.purchaseSuccess(paymentProperties("success", "google_play", "LEGACY", "store", "coins", "order",
                "play_consumed", amount = amount, currency = "USD"), "user")
            assertFalse(tracker.events.single().values.containsKey("af_revenue"))
        }
        val zero = RecordingTracker()
        zero.purchaseSuccess(paymentProperties("success", "google_play", "LEGACY", "store", "coins", "order",
            "play_consumed", amount = 0.0, currency = "USD"), "user")
        assertEquals(0.0, zero.events.single().values["af_revenue"])
        for (amount in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val tracker = RecordingTracker()
            tracker.purchaseSuccess(paymentProperties("success", "channel", "SERVICE", "store", "coins", "order", "fulfilled") +
                paymentPriceProperties(amount, "USD", "catalog_quote"), "user")
            assertFalse(tracker.events.single().values.containsKey("af_revenue"))
        }
        val thirdPartyZero = RecordingTracker()
        thirdPartyZero.purchaseSuccess(paymentProperties("success", "channel", "SERVICE", "store", "coins", "order", "fulfilled") +
            paymentPriceProperties(0.0, "USD", "catalog_quote"), "user")
        assertEquals(0.0, thirdPartyZero.events.single().values["af_revenue"])
    }

    @Test fun `公共白名单拒绝伪造状态与不一致金额`() {
        val valid = mapOf<String, Any>("status" to "success", "stage" to "fulfilled", "pay_method" to "channel",
            "price_source" to "catalog_quote", "af_price" to 2.99, "af_currency" to "USD", "af_revenue" to 2.99)
        assertFalse(businessRevenueParameters("initiate_pay", valid).containsKey("af_revenue"))
        assertFalse(businessRevenueParameters("purchase_success", valid + ("status" to "pending")).containsKey("af_revenue"))
        assertFalse(businessRevenueParameters("purchase_success", valid + ("stage" to "checkout_open")).containsKey("af_revenue"))
        assertFalse(businessRevenueParameters("purchase_success", valid + ("af_revenue" to 3.99)).containsKey("af_revenue"))
        assertEquals(2.99, businessRevenueParameters("purchase_success", valid)["af_revenue"])
    }
}
