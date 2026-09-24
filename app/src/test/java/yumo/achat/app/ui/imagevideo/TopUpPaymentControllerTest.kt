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
import yumo.achat.core.backend.StoreUserInfo
import yumo.achat.core.analytics.EventTracker

class TopUpPaymentControllerTest {
    @Test
    fun `first buy click uses displayed promotional price and bonus`() {
        val events = RecordingEvents()
        val controller = controller(FakeGateway(), events = events)
        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", firstBuy = true)),
            StoreUserInfo(currentDiamond = 0, hasMadeFirstPurchase = false, isVip = false),
        )

        controller.prepare()

        val event = events.single("click_package")
        assertEquals(1.99, event.parameters["af_price"])
        assertEquals(25L, event.parameters["bonus_amount"])
    }

    @Test
    fun `purchase action records package click with catalog quote`() {
        val events = RecordingEvents()
        val controller = controller(FakeGateway(), events = events)
        controller.retainAvailableProducts(listOf(product(id = "pack-100", googleProductId = "play.pack.100")))

        controller.prepare()

        val event = events.single("click_package")
        assertEquals("pack-100", event.parameters["package_id"])
        assertEquals("play.pack.100", event.parameters["af_content_id"])
        assertEquals(4.99, event.parameters["af_price"])
        assertEquals("USD", event.parameters["af_currency"])
        assertEquals(100L, event.parameters["diamond_amount"])
    }

    @Test
    fun `fulfilled official payment records result events once`() {
        val events = RecordingEvents()
        val gateway = FakeGateway(
            paymentStatus = {
                PaymentOrderStatus(it, "paid", "google_play", "fulfilled", null, null, null, null)
            },
        )
        val controller = controller(gateway, events = events)
        controller.retainAvailableProducts(listOf(product(id = "pack-100", googleProductId = "play.pack.100")))
        controller.prepare()
        val route = controller.state as TopUpPurchaseState.OfficialReady

        controller.onGooglePurchaseAccepted(route, pending = false)
        controller.onGooglePurchaseAccepted(route, pending = false)

        assertEquals(1, events.named("pay_result").size)
        assertEquals(1, events.named("payment_custom").size)
        assertEquals("success", events.single("pay_result").parameters["status"])
        assertEquals("fulfilled", events.single("pay_result").parameters["stage"])
    }

    @Test
    fun `google success uses play price for the single revenue event`() {
        val events = RecordingEvents()
        val gateway = FakeGateway(paymentStatus = {
            PaymentOrderStatus(it, "paid", "google_play", "fulfilled", null, null, null, null)
        })
        val controller = controller(gateway, events = events)
        controller.retainAvailableProducts(listOf(product(id = "pack-100", googleProductId = "play.pack.100")))
        controller.prepare()
        val route = controller.state as TopUpPurchaseState.OfficialReady

        controller.onGoogleBillingLaunched(route, 2.99, "USD")
        controller.onGooglePurchaseAccepted(route, pending = false)

        val result = events.single("payment_custom")
        assertEquals("google_play", result.parameters["price_source"])
        assertEquals(2.99, result.parameters["af_revenue"])
        assertEquals("USD", result.parameters["af_currency"])
    }

    @Test
    fun `terminal backend payment failure records controlled result`() {
        val events = RecordingEvents()
        val gateway = FakeGateway(paymentStatus = {
            PaymentOrderStatus(it, "failed", "google_play", "failed", null, null, null, null)
        })
        val controller = controller(gateway, events = events)
        controller.retainAvailableProducts(listOf(product(id = "pack-100", googleProductId = "play.pack.100")))
        controller.prepare()
        val route = controller.state as TopUpPurchaseState.OfficialReady

        controller.onGooglePurchaseAccepted(route, pending = false)

        val result = events.single("pay_result")
        assertEquals("failed", result.parameters["status"])
        assertEquals("server_status", result.parameters["stage"])
        assertEquals("payment_failed", result.parameters["fail_reason"])
    }

    @Test
    fun `opening third party checkout records link and payment initiation`() {
        val events = RecordingEvents()
        val gateway = FakeGateway(initialize = {
            PaymentInitialization(
                orderId = "order-1", channelType = "third_party", channelCode = "payu_web_us",
                openMode = "webview", paymentUrl = "https://checkout.example/pay/1", expiresAt = null,
                queryIntervalSeconds = 10, maxQuerySeconds = 600, sdkProductId = "",
            )
        })
        val controller = controller(gateway, events = events)
        controller.retainAvailableProducts(listOf(product(id = "pack-100")))
        controller.prepare()

        controller.openThirdParty(controller.state as TopUpPurchaseState.ThirdPartyReady)

        assertEquals(1, events.named("3rdpayment_link_ok").size)
        assertEquals(1, events.named("initiate_pay").size)
    }

    @Test
    fun `google cancellation and third party page load record controlled outcomes`() {
        val googleEvents = RecordingEvents()
        val google = controller(FakeGateway(), events = googleEvents)
        google.retainAvailableProducts(listOf(product(id = "pack-100", googleProductId = "play.pack.100")))
        google.prepare()
        val official = google.state as TopUpPurchaseState.OfficialReady
        google.beginOfficialCheckout(official)
        google.onGooglePurchaseCancelled(official)

        assertEquals("cancelled", googleEvents.single("pay_result").parameters["status"])
        assertEquals("user_cancelled", googleEvents.single("pay_result").parameters["fail_reason"])

        val webEvents = RecordingEvents()
        val web = controller(FakeGateway(initialize = {
            PaymentInitialization("order-1", "third_party", "payu_web_us", "webview",
                "https://checkout.example/pay/1", null, 10, 600, "")
        }), events = webEvents)
        web.retainAvailableProducts(listOf(product(id = "pack-100")))
        web.prepare()
        val route = web.state as TopUpPurchaseState.ThirdPartyReady
        web.openThirdParty(route)
        web.onThirdPartyPageLoaded(route)

        assertEquals(1, webEvents.named("3rdpayment_page_loaded").size)
    }

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
    fun `official payment uses initializer sdk sku`() {
        val gateway = FakeGateway()
        val controller = controller(gateway, flow = TopUpPaymentFlow.Service)

        controller.retainAvailableProducts(listOf(product(id = "pack-100")))
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("diamonds_100", route.sdkProductId)
    }

    @Test
    fun `legacy payment creates order and uses catalog google sku without initializer`() {
        val gateway = FakeGateway(
            initialize = { error("legacy must not initialize payment-service") },
        )
        val controller = controller(gateway, flow = TopUpPaymentFlow.Legacy)

        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(0, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("google_play", route.channelCode)
        assertEquals("play.pack.100", route.sdkProductId)
        assertEquals("order-1", route.orderId)
    }

    @Test
    fun `legacy payment falls back to product id when catalog google sku is blank`() {
        val gateway = FakeGateway(
            initialize = { error("legacy must not initialize payment-service") },
        )
        val controller = controller(gateway, flow = TopUpPaymentFlow.Legacy)

        controller.retainAvailableProducts(listOf(product(id = "pack-100")))
        controller.prepare()

        assertEquals(0, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("pack-100", route.sdkProductId)
    }

    @Test
    fun `legacy payment uses third party sku when google sku is blank`() {
        val gateway = FakeGateway(
            initialize = { error("legacy must not initialize payment-service") },
        )
        val controller = controller(gateway, flow = TopUpPaymentFlow.Legacy)

        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", thirdPartyProductId = "play.third.pack.100")),
        )
        controller.prepare()

        assertEquals(0, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("play.third.pack.100", route.sdkProductId)
    }

    @Test
    fun `catalog google sku does not override initializer route`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)

        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("diamonds_100", route.sdkProductId)
        assertEquals("google_play", route.channelCode)
    }

    @Test
    fun `third party initializer route opens checkout payload`() {
        val gateway = FakeGateway(
            initialize = {
                PaymentInitialization(
                    orderId = "order-1",
                    channelType = "third_party",
                    channelCode = "payu_web_us",
                    openMode = "webview",
                    paymentUrl = "https://checkout.example/pay/1",
                    expiresAt = "2026-09-23T08:10:00Z",
                    queryIntervalSeconds = 10,
                    maxQuerySeconds = 600,
                    sdkProductId = "",
                )
            },
        )
        val controller = controller(gateway)
        controller.selectProduct("pack-100")

        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.ThirdPartyReady
        assertEquals("payu_web_us", route.channelCode)
        assertEquals("https://checkout.example/pay/1", route.paymentUrl)
    }

    @Test
    fun `payment channel unavailable falls back to official google route`() {
        val gateway = FakeGateway(
            initialize = { error("PAYMENT_CHANNEL_UNAVAILABLE") },
        )
        val controller = controller(gateway)
        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )

        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.initializeCount)
        val route = controller.state as TopUpPurchaseState.OfficialReady
        assertEquals("google_play", route.channelCode)
        assertEquals("play.pack.100", route.sdkProductId)
        assertEquals("order-1", route.orderId)
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
        assertEquals(1, gateway.initializeCount)
    }

    @Test
    fun `initialize failure retry reuses created order`() {
        var initializeAttempts = 0
        val events = RecordingEvents()
        val gateway = FakeGateway(
            initialize = {
                initializeAttempts += 1
                if (initializeAttempts == 1) error("route unavailable")
                officialInitialization()
            },
        )
        val controller = controller(gateway, events = events)
        controller.selectProduct("pack-100")

        controller.prepare()
        assertTrue(controller.state is TopUpPurchaseState.Error)
        assertEquals("initialize", events.single("pay_result").parameters["stage"])
        controller.prepare()

        assertEquals(1, gateway.createCount)
        assertEquals(2, gateway.initializeCount)
        assertTrue(controller.state is TopUpPurchaseState.OfficialReady)
    }

    @Test
    fun `official timeout retry reuses prepared route without a new order`() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.retainAvailableProducts(
            listOf(product(id = "pack-100", googleProductId = "play.pack.100")),
        )
        controller.prepare()
        val route = controller.state as TopUpPurchaseState.OfficialReady
        controller.forceCheckoutState(TopUpCheckoutState.TimedOut)

        controller.prepare()

        assertEquals(TopUpCheckoutState.Idle, controller.checkoutState)
        assertEquals(route, controller.state)
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

    @Test
    fun `restored fulfilled purchase records isolated result without replacing checkout state`() {
        val events = RecordingEvents()
        val gateway = FakeGateway(paymentStatus = {
            PaymentOrderStatus(it, "paid", "google_play", "fulfilled", null, null, null, null)
        })
        val controller = controller(gateway, events = events)

        controller.reconcileGooglePurchases(
            listOf(RecoveredGooglePurchase("restored-order", pending = false, sdkProductId = "play.pack.100")),
        )

        val result = events.single("pay_result")
        assertEquals("restored", result.parameters["payment_flow"])
        assertEquals("restored-order", result.parameters["af_order_id"])
        assertEquals("play.pack.100", result.parameters["af_content_id"])
        assertEquals(TopUpCheckoutState.Idle, controller.checkoutState)
    }

    private fun controller(
        gateway: FakeGateway,
        flow: TopUpPaymentFlow = TopUpPaymentFlow.Service,
        events: EventTracker = yumo.achat.core.analytics.NoOpEventTracker,
    ) = TopUpPaymentController(
        gateway = gateway,
        paymentFlow = flow,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        errorMessage = { it.message ?: "Payment failed" },
        events = events,
        userId = { "user-1" },
    )

    private class RecordingEvents : EventTracker {
        data class Event(val name: String, val parameters: Map<String, Any>, val userId: String?)
        private val values = mutableListOf<Event>()
        override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
            values += Event(name, parameters, userId)
        }
        fun named(name: String) = values.filter { it.name == name }
        fun single(name: String) = named(name).single()
    }

    private fun TopUpPaymentController.forceCheckoutState(value: TopUpCheckoutState) {
        javaClass.getDeclaredMethod("setCheckoutState", TopUpCheckoutState::class.java)
            .apply { isAccessible = true }
            .invoke(this, value)
    }

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

        private fun product(
            id: String,
            googleProductId: String = "",
            thirdPartyProductId: String = "",
            firstBuy: Boolean = false,
        ) = StoreProduct(
            id = id,
            name = "Diamonds",
            description = "",
            type = "diamond",
            value = 100,
            bonusValue = 0,
            firstBuyBonusValue = if (firstBuy) 25 else 0,
            currency = "USD",
            originalPrice = BigDecimal("4.99"),
            price = BigDecimal("4.99"),
            firstBuyPrice = if (firstBuy) BigDecimal("1.99") else BigDecimal.ZERO,
            discountRate = BigDecimal.ZERO,
            firstBuyDiscount = BigDecimal.ZERO,
            icon = "",
            isFirstBuyPromotion = firstBuy,
            isPromotion = false,
            isSubscription = false,
            promotionType = "",
            sortOrder = 0,
            tags = "",
            thirdPartyProductId = thirdPartyProductId,
            googleProductId = googleProductId,
            vipLevel = 0,
        )
    }
}
