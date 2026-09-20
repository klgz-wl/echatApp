package yumo.achat.app.ui.imagevideo

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.app.data.backend.PaymentInitialization
import yumo.achat.app.data.backend.PreparedStorePayment
import yumo.achat.app.data.backend.StoreOrder
import yumo.achat.app.data.backend.PaymentOrderStatus

class TopUpPaymentPreparationTest {
    @Test
    fun `official route keeps server sdk sku and obfuscated ids`() {
        val state = preparedPayment(
            PaymentInitialization(
                orderId = "order-1",
                channelType = "official",
                channelCode = "google_play",
                openMode = "sdk",
                paymentUrl = "",
                expiresAt = null,
                queryIntervalSeconds = 0,
                maxQuerySeconds = 0,
                sdkProductId = "diamonds_100_first",
            ),
        ).toTopUpPurchaseState("pack-100")

        assertTrue(state is TopUpPurchaseState.OfficialReady)
        state as TopUpPurchaseState.OfficialReady
        assertEquals("diamonds_100_first", state.sdkProductId)
        assertEquals("account-hash", state.obfuscatedAccountId)
        assertEquals("order-1", state.obfuscatedProfileId)
    }

    @Test
    fun `third party route keeps checkout payload`() {
        val state = preparedPayment(
            PaymentInitialization(
                orderId = "order-1",
                channelType = "third_party",
                channelCode = "payu_web_us",
                openMode = "webview",
                paymentUrl = "https://checkout.example/pay/1",
                expiresAt = "2026-09-20T08:10:00Z",
                queryIntervalSeconds = 10,
                maxQuerySeconds = 600,
                sdkProductId = "",
            ),
        ).toTopUpPurchaseState("pack-100")

        assertTrue(state is TopUpPurchaseState.ThirdPartyReady)
        state as TopUpPurchaseState.ThirdPartyReady
        assertEquals("https://checkout.example/pay/1", state.paymentUrl)
        assertEquals(10, state.queryIntervalSeconds)
    }

    @Test
    fun `invalid initialized route is rejected before checkout`() {
        assertThrows(IllegalStateException::class.java) {
            preparedPayment(
                PaymentInitialization(
                    orderId = "order-1",
                    channelType = "official",
                    channelCode = "google_play",
                    openMode = "sdk",
                    paymentUrl = "",
                    expiresAt = null,
                    queryIntervalSeconds = 0,
                    maxQuerySeconds = 0,
                    sdkProductId = "",
                ),
            ).toTopUpPurchaseState("pack-100")
        }
    }

    @Test
    fun `third party checkout requires https`() {
        assertThrows(IllegalStateException::class.java) {
            preparedPayment(
                PaymentInitialization(
                    orderId = "order-1",
                    channelType = "third_party",
                    channelCode = "payu_web_us",
                    openMode = "webview",
                    paymentUrl = "http://checkout.example/pay/1",
                    expiresAt = null,
                    queryIntervalSeconds = 10,
                    maxQuerySeconds = 600,
                    sdkProductId = "",
                ),
            ).toTopUpPurchaseState("pack-100")
        }
    }

    @Test
    fun `order product must match selected product`() {
        assertThrows(IllegalStateException::class.java) {
            preparedPayment(
                PaymentInitialization(
                    orderId = "order-1",
                    channelType = "official",
                    channelCode = "google_play",
                    openMode = "sdk",
                    paymentUrl = "",
                    expiresAt = null,
                    queryIntervalSeconds = 0,
                    maxQuerySeconds = 0,
                    sdkProductId = "diamonds_100",
                ),
            ).toTopUpPurchaseState("different-product")
        }
    }

    @Test
    fun `payment succeeds only after paid and fulfilled`() {
        assertEquals(
            TopUpPaymentOutcome.Success,
            classifyTopUpPayment(PaymentOrderStatus("o", "paid", "google_play", "fulfilled", null, null, null, null)),
        )
        assertEquals(
            TopUpPaymentOutcome.Pending,
            classifyTopUpPayment(PaymentOrderStatus("o", "paid", "google_play", "pending", null, null, null, null)),
        )
        assertEquals(
            TopUpPaymentOutcome.Failed,
            classifyTopUpPayment(PaymentOrderStatus("o", "expired", "payu_web_us", "pending", null, null, null, null)),
        )
    }

    private fun preparedPayment(initialization: PaymentInitialization) = PreparedStorePayment(
        order = StoreOrder(
            id = "order-1",
            number = "ORD-1",
            productId = "pack-100",
            productName = "100 Diamonds",
            amount = BigDecimal("4.99"),
            currency = "USD",
            status = "pending",
            createdAt = "2026-09-20T08:00:00Z",
            paymentUrl = "",
            obfuscatedAccountId = "account-hash",
            obfuscatedProfileId = "order-1",
        ),
        initialization = initialization,
    )
}
