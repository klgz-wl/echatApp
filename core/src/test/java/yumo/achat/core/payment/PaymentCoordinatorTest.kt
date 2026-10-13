package yumo.achat.core.payment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentCoordinatorTest {
    @Test fun `前台自动轮询跳过外部浏览器收银台避免误判关闭`() {
        val externalBrowser = PaymentRecord(
            key = "key",
            userId = "user",
            productId = "coins",
            source = "main",
            orderId = "order",
            initialized = InitializedPayment(
                orderId = "order",
                channelType = "third_party",
                channelCode = "payu_web",
                openMode = "external_browser",
                paymentUrl = "https://pay.example/checkout",
                expiresAt = "2099-01-01T00:00:00Z",
            ),
            stage = PaymentStage.CHECKOUT,
            openedAt = 1_000L,
        )
        val webView = externalBrowser.copy(
            initialized = externalBrowser.initialized!!.copy(openMode = "webview"),
        )
        val official = externalBrowser.copy(
            initialized = InitializedPayment(
                orderId = "order",
                channelType = "official",
                channelCode = "google_play",
                openMode = "sdk",
                sdkParams = PaymentSdkParams("sku"),
            ),
            stage = PaymentStage.OFFICIAL_LAUNCHED,
        )

        assertFalse(shouldAutoPollPayment(externalBrowser))
        assertTrue(shouldAutoPollPayment(webView))
        assertTrue(shouldAutoPollPayment(official))
    }
}
