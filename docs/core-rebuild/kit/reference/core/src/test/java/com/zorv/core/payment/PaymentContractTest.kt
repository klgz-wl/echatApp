package com.zorv.core.payment

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaymentContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun `初始化请求仅包含业务订单且独立封装可解析`() {
        assertEquals("{\"order_id\":\"order\"}", json.encodeToString(InitializePaymentRequest("order")))
        val response = json.decodeFromString<PaymentEnvelope<InitializedPayment>>("""{"data":{"order_id":"order","channel_type":"official","channel_code":"google_play","open_mode":"sdk","sdk_params":{"product_id":"server-sku"},"extra":1}}""")
        assertEquals("server-sku", response.requireData().validate("order").sdkParams?.productId)
    }
    @Test fun `缺少SKU订单错配或未知渠道禁止拉起支付`() {
        val valid = InitializedPayment("order", "official", "google_play", "sdk", PaymentSdkParams("sku"))
        listOf(valid.copy(orderId="other"), valid.copy(sdkParams=null), valid.copy(channelType="unknown"), valid.copy(openMode="webview")).forEach {
            try { it.validate("order"); fail() } catch (_: PaymentFailure.InvalidResponse) { }
        }
    }
    @Test fun `收银台只接受HTTPS和有效到期时间`() {
        val valid = InitializedPayment("order", "third_party", "channel", "webview", paymentUrl="https://pay.example/checkout?secret=x", expiresAt="2026-09-14T12:00:00Z")
        valid.validate("order")
        listOf("http://pay.example", "javascript:alert(1)", "https://user:password@pay.example", "file:///tmp/pay", "").forEach {
            try { valid.copy(paymentUrl=it).validate("order"); fail() } catch (_: PaymentFailure.InvalidResponse) { }
        }
        assertNull(paymentTime("2026-02-31T00:00:00Z"))
        assertEquals(paymentTime("2026-09-14T12:00:00.123Z"), paymentTime("2026-09-14T20:00:00.123456+08:00"))
        assertFalse(valid.toString().contains("secret"))
    }
    @Test fun `仅已支付且已发币成功未知值不得成功`() {
        listOf("pending", "failed", "cancelled", "expired", "unknown").forEach { assertFalse(PaymentOrderStatus("order", it, "fulfilled").successful) }
        assertFalse(PaymentOrderStatus("order", "paid", "pending").successful)
        assertFalse(PaymentOrderStatus("order", "paid", null).successful)
        assertTrue(PaymentOrderStatus("order", "paid", "fulfilled").successful)
    }
    @Test fun `轮询配置拒绝非法值且服务端不能扩展十分钟`() {
        val config = PaymentConfiguration("", "test", 10, 600, 3000, 100, 3, 5000)
        assertEquals(10, config.interval(-1)); assertEquals(600, config.duration(900))
        assertEquals(60, config.duration(60))
        try { PaymentEnvelope<String>("bad", 1).requireData(); fail() } catch (_: PaymentFailure.InvalidResponse) { }
    }
}
