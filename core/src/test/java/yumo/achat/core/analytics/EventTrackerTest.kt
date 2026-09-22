package yumo.achat.core.analytics

import org.junit.Assert.*
import org.junit.Test

class EventTrackerTest {
    @Test fun `四个平台使用新事件名和单字母前缀`() {
        assertEquals(listOf("f_payment_custom", "a_payment_custom", "s_payment_custom", "z_payment_custom"), AnalyticsPlatform.entries.map { it.eventName("payment_custom") })
    }
    @Test fun `布尔数值规范一致且拒绝非有限金额和复杂对象`() {
        val result = analyticsParameters(mapOf("is_success" to true, "count" to 3, "amount" to 1.5, "invalid" to Double.NaN,
            "nested" to mapOf("token" to "secret"), "bad-key" to "bad", "description" to "x".repeat(120)))
        assertEquals(1L, result["is_success"]); assertEquals(3L, result["count"]); assertEquals(1.5, result["amount"])
        assertFalse(result.containsKey("invalid")); assertFalse(result.containsKey("nested")); assertFalse(result.containsKey("bad-key"))
        assertEquals(100, (result["description"] as String).length)
        assertEquals(25, analyticsParameters((1..30).associate { "key$it" to it }).size)
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
}
