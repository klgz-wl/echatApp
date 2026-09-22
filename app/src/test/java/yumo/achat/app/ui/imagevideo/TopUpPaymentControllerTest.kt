package yumo.achat.app.ui.imagevideo

import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.backend.PaymentInitialization
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreOrder
import yumo.achat.core.backend.StorePaymentGateway
import yumo.achat.core.backend.PaymentOrderStatus

class TopUpPaymentControllerTest {
    @Test
    fun `switching product prevents stale payment route from being applied`() {
        val createGate = CompletableDeferred<StoreOrder>()
        val gateway = FakeGateway(createOrder = { createGate.await() })
        val controller = controller(gateway)

        controller.selectProduct("pack-a")
        controller.prepare()
        controller.selectProduct("pack-b")
        createGate.complete(order("pack-a"))

        assertEquals("pack-b", controller.selectedProductId)
        assertEquals(TopUpPurchaseState.Idle, controller.state)
    }

    @Test
    fun `official payment falls back to product id when catalog google sku is absent`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)

        controller.retainAvailableProducts(listOf(product(id = "pack-100")))
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(0, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("pack-100", route.sdkProductId)
    }

    @Test
    fun `official payment directly uses catalog google sku without payment initialization`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)

        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(0, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("play.pack.100", route.sdkProductId)
        assertEquals("google_play", route.channelCode)
    }

    @Test
    fun `mismatched created order is rejected before payment initialization`() {
        val gateway = FakeGateway(createOrder = { order("different-product") })
        val controller = controller(gateway)

        controller.selectProduct("pack-100")
        controller.prepare()

        assertEquals(0, gateway.initializeCount)
        assertTrue(controller.state is TopUpPurchaseState.Error)
        assertEquals(null, (controller.state as TopUpPurchaseState.Error).order)
    }

    @Test
    fun `official polling succeeds only after backend fulfillment`() {
        val gateway = FakeGateway(
            paymentStatus = {
                PaymentOrderStatus(it, "paid", "payu_web_us", "fulfilled", null, null, null, null)
            },
        )
        val controller = controller(gateway)
        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )
        controller.prepare()
        val route = controller.state as TopUpPurchaseState.OfficialReady

        controller.onGooglePurchaseAccepted(route, pending = false)

        assertTrue(controller.checkoutState is TopUpCheckoutState.Succeeded)
        assertEquals(emptyList<String>(), gateway.events)
    }

    @Test
    fun `terminal checkout failure can relaunch the prepared route without a new order`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.selectProduct("pack-100")
        controller.prepare()
        controller.onCheckoutLaunchError("Play unavailable")

        controller.prepare()

        assertEquals(TopUpCheckoutState.Idle, controller.checkoutState)
        assertEquals(1, gateway.createCount)
    }

    @Test
    fun `restore lookup blocks new order until lookup completes`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.selectProduct("pack-100")
        controller.beginReconciliationLookup()

        controller.prepare()
        assertEquals(0, gateway.createCount)

        controller.completeReconciliationLookup(emptyList())
        controller.prepare()
        assertEquals(1, gateway.createCount)
    }

    private fun controller(gateway: FakeGateway) = TopUpPaymentController(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        errorMessage = { it.message ?: "Payment failed" },
    )

    private class FakeGateway(
        private val createOrder: suspend (String) -> StoreOrder = { order(it) },
        private val initialize: suspend (String) -> PaymentInitialization = { officialInitialization() },
        private val paymentStatus: suspend (String) -> PaymentOrderStatus = {
            PaymentOrderStatus(it, "pending", "", "pending", null, null, null, null)
        },
    ) : StorePaymentGateway {
        var createCount = 0
        var initializeCount = 0
        val events = mutableListOf<String>()

        override suspend fun createStoreOrder(productId: String): StoreOrder {
            createCount += 1
            return createOrder(productId)
        }

        override suspend fun initializeStorePayment(orderId: String): PaymentInitialization {
            initializeCount += 1
            return initialize(orderId)
        }

        override suspend fun storePaymentStatus(orderId: String) = paymentStatus(orderId)

        override suspend fun reportStorePaymentEvent(
            orderId: String,
            eventType: String,
            channelCode: String,
            openMode: String,
            url: String,
            errorCode: String,
            errorMessage: String,
        ) {
            events += eventType
        }
    }

    companion object {
        private fun order(productId: String) = StoreOrder(
            id = "order-1",
            number = "ORD-1",
            productId = productId,
            productName = "Diamonds",
            amount = BigDecimal("4.99"),
            currency = "USD",
            status = "pending",
            createdAt = "2026-09-20T08:00:00Z",
            paymentUrl = "",
            obfuscatedAccountId = "account-hash",
            obfuscatedProfileId = "order-1",
        )

        private fun officialInitialization() = PaymentInitialization(
            orderId = "order-1",
            channelType = "official",
            channelCode = "google_play",
            openMode = "sdk",
            paymentUrl = "",
            expiresAt = null,
            queryIntervalSeconds = 0,
            maxQuerySeconds = 0,
            sdkProductId = "diamonds_100",
        )

        private fun product(id: String, googleProductId: String = "") = StoreProduct(
            id = id,
            name = "Diamonds",
            description = "",
            type = "diamond",
            value = 100,
            bonusValue = 0,
            firstBuyBonusValue = 0,
            currency = "USD",
            originalPrice = BigDecimal("4.99"),
            price = BigDecimal("4.99"),
            firstBuyPrice = BigDecimal.ZERO,
            discountRate = BigDecimal.ZERO,
            firstBuyDiscount = BigDecimal.ZERO,
            icon = "",
            isFirstBuyPromotion = false,
            isPromotion = false,
            isSubscription = false,
            promotionType = "",
            sortOrder = 0,
            tags = "",
            thirdPartyProductId = "",
            googleProductId = googleProductId,
            vipLevel = 0,
        )
    }
}
