package yumo.achat.core.analytics

import org.junit.Assert.*
import org.junit.Test

class EventTrackerTest {
    @Test fun `四个平台使用平台契约事件名前缀`() {
        assertEquals(listOf("f_payment_custom", "af_payment_custom", "s_payment_custom", "z_payment_custom"), AnalyticsPlatform.entries.map { it.eventName("payment_custom") })
    }
    @Test fun `布尔数值规范一致且拒绝非有限金额和复杂对象`() {
        val result = analyticsParameters(mapOf("is_success" to true, "count" to 3, "amount" to 1.5, "invalid" to Double.NaN,
            "nested" to mapOf("token" to "secret"), "bad-key" to "bad", "description" to "x".repeat(120)))
        assertEquals(1L, result["is_success"]); assertEquals(3L, result["count"]); assertEquals(1.5, result["amount"])
        assertFalse(result.containsKey("invalid")); assertFalse(result.containsKey("nested")); assertFalse(result.containsKey("bad-key"))
        assertEquals(100, (result["description"] as String).length)
        assertEquals(30, analyticsParameters((1..30).associate { "key$it" to it }).size)
    }
    @Test fun `支付参数使用官方名称且仅成功主事件携带收入`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "google_play", "LEGACY", "home", "package", "order",
            "play_consumed", amount = 2.99, currency = "usd")
        tracker.paymentResult(values, "user")
        val result = tracker.events.first().values
        val main = tracker.events.last().values
        assertEquals(2.99, result["af_price"])
        assertEquals("USD", result["af_currency"])
        assertEquals("order", result["af_order_id"])
        assertEquals("true", result["af_success"])
        assertFalse(result.containsKey("af_revenue"))
        assertEquals(2.99, main["af_revenue"])
        assertEquals("recharge", main["af_content_type"])
        for (key in listOf("amount", "currency", "price", "value", "order_id", "type", "is_success")) {
            assertFalse(result.containsKey(key)); assertFalse(main.containsKey(key))
        }
    }
    @Test fun `支付事件补齐AF充值兼容字段`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("failed", "google_play", "SERVICE", "generation", "coins_100", "order-1",
            "sdk_launch", "billing_unavailable", amount = 1.99, currency = "usd") + mapOf("af_content_id" to "play.sku.100")

        tracker.paymentResult(values, "user")

        val result = tracker.events.single { it.name == "pay_result" }.values
        val custom = tracker.events.single { it.name == "payment_custom" }.values
        for (event in listOf(result, custom)) {
            assertEquals("recharge", event["af_content_type"])
            assertEquals("user", event["self_user_id"])
            assertEquals("generation", event["trigger"])
            assertEquals("billing_unavailable", event["failed_reason"])
            assertEquals("false", event["af_success"])
            assertEquals("play.sku.100", event["af_content_id"])
        }
    }
    @Test fun `取消失败待处理无金额或无币种时不记录收入`() {
        for (status in listOf("cancelled", "failed", "pending", "unknown", "closed", "initiated")) {
            val tracker = RecordingTracker()
            tracker.paymentResult(paymentProperties(status, "google_play", "LEGACY", "home", "package", stage = "sdk_launch",
                amount = 2.99, currency = "USD"), "user")
            assertTrue(tracker.events.none { "af_revenue" in it.values })
        }
        for ((amount, currency) in listOf(null to "USD", Double.NaN to "USD", -1.0 to "USD", 2.99 to null, 2.99 to "", 2.99 to "$")) {
            val tracker = RecordingTracker()
            tracker.paymentResult(paymentProperties("success", "google_play", "LEGACY", "home", "package", stage = "play_consumed",
                amount = amount, currency = currency), "user")
            assertTrue(tracker.events.none { "af_revenue" in it.values })
        }
    }
    @Test fun `报价使用官方价格币种字段但不会当作成功收入`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "google_play", "SERVICE", "home", "package", stage = "fulfilled") +
            paymentPriceProperties(5.99, "EUR", "catalog_quote")
        tracker.paymentResult(values, "user")
        assertTrue(tracker.events.all { it.values["af_price"] == 5.99 && it.values["af_currency"] == "EUR" })
        assertTrue(tracker.events.none { "af_revenue" in it.values })
    }
    @Test fun `purchase success第三方报价不伪造成真实收入`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "channel", "SERVICE", "home", "package", "order",
            "fulfilled") + paymentPriceProperties(5.99, "EUR", "catalog_quote")

        tracker.purchaseSuccess(values, "user")

        val event = tracker.events.single()
        assertEquals(5.99, event.values["af_price"])
        assertEquals("EUR", event.values["af_currency"])
        assertFalse(event.values.containsKey("af_revenue"))
    }
    @Test fun `取消待处理未知不当作成功且未知实付金额不伪造`() {
        for (status in listOf("cancelled", "pending", "closed", "unknown", "failed")) {
            val values = paymentProperties(status, "google_play", "LEGACY", "home_balance", "package", stage = "sdk_launch")
            assertEquals("false", values["af_success"]); assertFalse(values.containsKey("af_price")); assertFalse(values.containsKey("af_currency"))
        }
    }
    @Test fun `结果双事件各自一次按账号和订单隔离`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "google_play", "LEGACY", "video_balance", "package", "order", "play_consumed", amount = 2.99, currency = "USD")
        repeat(3) { tracker.paymentResult(values, "user", "play:order:consumed") }
        assertEquals(listOf("pay_result", "payment_custom"), tracker.events.map { it.name })
        assertEquals("video_balance", tracker.events.last().values["entry_source"])
        assertEquals(1, tracker.events.count { "af_revenue" in it.values })
        tracker.paymentResult(values, "another", "play:order:consumed")
        assertEquals(4, tracker.events.size)
    }

    @Test fun `purchase success替代旧purchase client并携带完整AF支付参数`() {
        val tracker = RecordingTracker()
        val values = paymentProperties("success", "google_play", "LEGACY", "store", "coins_100", "order-1",
            "play_consumed", amount = 4.99, currency = "usd") + mapOf("af_content_id" to "play.sku.100")

        tracker.paymentResult(values, "user", "play:order-1:consumed", includeRevenue = false)
        repeat(3) { tracker.purchaseSuccess(values, "user", "play:order-1:purchase_success") }

        assertEquals(listOf("pay_result", "payment_custom", "purchase_success"), tracker.events.map { it.name })
        assertTrue(tracker.events.filter { it.name != "purchase_success" }.none { "af_revenue" in it.values })
        val event = tracker.events.single { it.name == "purchase_success" }
        assertEquals("user", event.user)
        assertEquals("play:order-1:purchase_success", event.once)
        assertEquals("success", event.values["status"])
        assertEquals("true", event.values["af_success"])
        assertEquals("google_play", event.values["pay_method"])
        assertEquals("LEGACY", event.values["payment_flow"])
        assertEquals("store", event.values["entry_source"])
        assertEquals("coins_100", event.values["package_id"])
        assertEquals("order-1", event.values["af_order_id"])
        assertEquals("play.sku.100", event.values["af_content_id"])
        assertEquals("recharge", event.values["af_content_type"])
        assertEquals("user", event.values["self_user_id"])
        assertEquals("store", event.values["trigger"])
        assertEquals(4.99, event.values["af_price"])
        assertEquals(4.99, event.values["af_revenue"])
        assertEquals("USD", event.values["af_currency"])
        assertFalse(tracker.events.any { it.name.startsWith("purchase_client") })
    }
}
