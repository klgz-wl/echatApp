package yumo.achat.app.ui.imagevideo

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import yumo.achat.app.BuildConfig
import yumo.achat.app.R
import yumo.achat.app.analytics.AchatAnalyticsRuntime
import yumo.achat.core.backend.PreparedStorePayment
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreOrder
import yumo.achat.core.backend.StoreUserInfo
import yumo.achat.core.backend.StorePaymentGateway
import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.analytics.NoOpEventTracker
import yumo.achat.core.analytics.paymentResult

internal class TopUpPaymentController(
    private val gateway: StorePaymentGateway,
    private val paymentFlow: TopUpPaymentFlow,
    private val scope: CoroutineScope,
    private val errorMessage: (Throwable) -> String,
    private val events: EventTracker = NoOpEventTracker,
    private val userId: () -> String? = { null },
) {
    var selectedProductId by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf<TopUpPurchaseState>(TopUpPurchaseState.Idle)
        private set

    var checkoutState by mutableStateOf<TopUpCheckoutState>(TopUpCheckoutState.Idle)
        private set

    var successSerial by mutableStateOf(0L)
        private set

    var reconciliationCount by mutableStateOf(0)
        private set

    private var preparationJob: Job? = null
    private var pollingJob: Job? = null
    private var refreshJob: Job? = null
    private var requestSerial = 0L
    private val successfulOrderIds = mutableSetOf<String>()
    private var officialProductIds: Map<String, String> = emptyMap()
    private var products: Map<String, StoreProduct> = emptyMap()
    private var storeUserInfo: StoreUserInfo? = null
    private val playQuotes = mutableMapOf<String, Pair<Double, String>>()

    fun beginReconciliationLookup() {
        reconciliationCount += 1
    }

    fun completeReconciliationLookup(purchases: List<RecoveredGooglePurchase>) {
        reconciliationCount = (reconciliationCount - 1).coerceAtLeast(0)
        reconcileGooglePurchases(purchases)
    }

    fun selectProduct(productId: String?) {
        if (selectedProductId == productId) return
        selectedProductId = productId
        requestSerial += 1
        preparationJob?.cancel()
        preparationJob = null
        state = TopUpPurchaseState.Idle
        checkoutState = TopUpCheckoutState.Idle
        pollingJob?.cancel()
        refreshJob?.cancel()
    }

    fun retainAvailableProducts(products: List<StoreProduct>, userInfo: StoreUserInfo? = null) {
        storeUserInfo = userInfo
        this.products = products.associateBy(StoreProduct::id)
        officialProductIds = products.associate { product -> product.id to product.officialProductId }
        val productIds = products.map { it.id }
        val retained = selectedProductId?.takeIf { it in productIds }
        selectProduct(retained ?: productIds.firstOrNull())
    }

    fun prepare() {
        val productId = selectedProductId ?: return
        if (reconciliationCount > 0) return
        val failedCheckout = checkoutState as? TopUpCheckoutState.Failed
        if ((failedCheckout?.retryPreparedRoute == true || checkoutState == TopUpCheckoutState.Cancelled) &&
            (state is TopUpPurchaseState.OfficialReady || state is TopUpPurchaseState.ThirdPartyReady)
        ) {
            checkoutState = TopUpCheckoutState.Idle
            return
        }
        if (checkoutState == TopUpCheckoutState.TimedOut && state is TopUpPurchaseState.OfficialReady) {
            checkoutState = TopUpCheckoutState.Idle
            return
        }
        if (failedCheckout != null || checkoutState == TopUpCheckoutState.TimedOut ||
            checkoutState is TopUpCheckoutState.Succeeded
        ) {
            state = TopUpPurchaseState.Idle
            checkoutState = TopUpCheckoutState.Idle
        }
        if (state is TopUpPurchaseState.Preparing ||
            state is TopUpPurchaseState.OfficialReady ||
            state is TopUpPurchaseState.ThirdPartyReady
        ) {
            return
        }
        trackPackageClick(productId)
        val existingOrder = (state as? TopUpPurchaseState.Error)
            ?.takeIf { it.productId == productId }
            ?.order
        val serial = ++requestSerial
        state = TopUpPurchaseState.Preparing(productId)
        preparationJob = scope.launch {
            var order: StoreOrder? = existingOrder
            try {
                if (order == null) {
                    order = gateway.createStoreOrder(productId)
                }
                if (order.productId != productId) {
                    order = null
                    error("Payment order product mismatch")
                }
                val preparedState = when (paymentFlow) {
                    TopUpPaymentFlow.Legacy -> officialFallbackRoute(productId, order)
                    TopUpPaymentFlow.Service -> {
                        val initialization = gateway.initializeStorePayment(order.id)
                        PreparedStorePayment(order, initialization).toTopUpPurchaseState(productId)
                    }
                }
                if (requestSerial == serial && selectedProductId == productId) {
                    state = preparedState
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestSerial == serial && selectedProductId == productId) {
                    if (order != null && isPaymentChannelUnavailableMessage(error.message)) {
                        state = officialFallbackRoute(productId, order)
                        return@launch
                    }
                    emitPreparationFailure(
                        productId = productId,
                        order = order,
                        stage = if (order == null) "order_creation" else "initialize",
                        serial = serial,
                    )
                    state = TopUpPurchaseState.Error(
                        productId = productId,
                        message = errorMessage(error),
                        order = order,
                    )
                }
            }
        }
    }

    private fun officialFallbackRoute(productId: String, order: StoreOrder): TopUpPurchaseState.OfficialReady =
        TopUpPurchaseState.OfficialReady(
            productId = productId,
            order = order,
            channelCode = "google_play",
            sdkProductId = officialProductIds[productId].orEmpty().ifBlank { productId },
        )

    fun beginOfficialCheckout(route: TopUpPurchaseState.OfficialReady) {
        if (state != route || checkoutState != TopUpCheckoutState.Idle) return
        checkoutState = TopUpCheckoutState.Launching
    }

    fun onGoogleBillingLaunched(route: TopUpPurchaseState.OfficialReady, price: Double, currency: String) {
        if (state != route) return
        if (price.isFinite() && price >= 0 && currency.matches(Regex("[A-Z]{3}"))) {
            playQuotes[route.orderId] = price to currency
        }
        events.track(
            "initiate_pay",
            paymentAnalyticsValues(route, "initiated", "sdk_launch"),
            userId(),
            "payment:${route.orderId}:initiate",
        )
    }

    fun onGooglePurchaseAccepted(route: TopUpPurchaseState.OfficialReady, pending: Boolean) {
        if (state != route) return
        checkoutState = TopUpCheckoutState.AwaitingPayment(pending)
        if (pending) emitPaymentResult(route, "pending", "play_pending", null, "pending")
        startPolling(route.order.id, route.channelCode, "sdk", 3, 600)
    }

    fun onGooglePurchaseCancelled(route: TopUpPurchaseState.OfficialReady) {
        if (state == route) {
            emitPaymentResult(route, "cancelled", "purchase", "user_cancelled", "cancelled")
            checkoutState = TopUpCheckoutState.Cancelled
        }
    }

    fun onCheckoutLaunchError(message: String) {
        emitCurrentPaymentResult("failed", "purchase", "payment_failed", "launch_error")
        checkoutState = TopUpCheckoutState.Failed(message, retryPreparedRoute = true)
    }

    fun openThirdParty(route: TopUpPurchaseState.ThirdPartyReady) {
        if (state != route || checkoutState != TopUpCheckoutState.Idle) return
        checkoutState = TopUpCheckoutState.AwaitingPayment(false)
        events.track(
            name = "3rdpayment_link_ok",
            parameters = paymentAnalyticsValues(route, status = "initiated", stage = "checkout_open") + mapOf(
                "channel_code" to route.channelCode,
                "open_mode" to route.openMode,
                "page_name" to "recharge_paywall",
            ),
            userId = userId(),
            onceKey = "payment:${route.orderId}:link_ok",
        )
        events.track(
            name = "initiate_pay",
            parameters = paymentAnalyticsValues(route, status = "initiated", stage = "checkout_open"),
            userId = userId(),
            onceKey = "payment:${route.orderId}:initiate",
        )
        reportEvent(route, "link_ok")
        reportEvent(route, "page_opened")
        startPolling(
            route.order.id,
            route.channelCode,
            route.openMode,
            route.queryIntervalSeconds,
            route.maxQuerySeconds,
        )
    }

    fun onThirdPartyPageLoaded(route: TopUpPurchaseState.ThirdPartyReady) {
        events.track(
            name = "3rdpayment_page_loaded",
            parameters = paymentAnalyticsValues(route, "initiated", "checkout_open") + mapOf(
                "channel_code" to route.channelCode,
                "open_mode" to route.openMode,
                "page_name" to "recharge_paywall",
            ),
            userId = userId(),
            onceKey = "payment:${route.orderId}:page_loaded",
        )
        reportEvent(route, "page_loaded")
    }

    fun onThirdPartyPageError(route: TopUpPurchaseState.ThirdPartyReady, message: String) {
        reportEvent(route, "page_load_error", "webview_error", message)
    }

    fun closeThirdParty(route: TopUpPurchaseState.ThirdPartyReady) {
        if (state != route || checkoutState == TopUpCheckoutState.Closing) return
        pollingJob?.cancel()
        val serial = requestSerial
        checkoutState = TopUpCheckoutState.Closing
        pollingJob = scope.launch {
            val outcome = try {
                classifyTopUpPayment(gateway.storePaymentStatus(route.orderId))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                TopUpPaymentOutcome.Pending
            }
            if (serial != requestSerial || state != route) return@launch
            if (outcome == TopUpPaymentOutcome.Success) {
                reportEvent(route, "paid_on_close")
                markSuccess(route.orderId)
            } else {
                emitCurrentPaymentResult("closed", "checkout_close", null, "closed")
                reportEvent(route, "user_cancel")
                checkoutState = TopUpCheckoutState.Cancelled
            }
        }
    }

    fun refreshPayment(route: TopUpPurchaseState.ThirdPartyReady) {
        if (state != route || checkoutState !is TopUpCheckoutState.AwaitingPayment) return
        val serial = requestSerial
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val outcome = try {
                classifyTopUpPayment(gateway.storePaymentStatus(route.orderId))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@launch
            }
            if (serial != requestSerial || state != route) return@launch
            when (outcome) {
                TopUpPaymentOutcome.Success -> markSuccess(route.orderId)
                TopUpPaymentOutcome.Failed -> {
                    emitCurrentPaymentResult("failed", "server_status", "payment_failed", "failed")
                    checkoutState = TopUpCheckoutState.Failed("Payment failed")
                }
                else -> Unit
            }
        }
    }

    fun reconcileGooglePurchases(purchases: List<RecoveredGooglePurchase>) {
        val unique = purchases.distinctBy { it.orderId }.filter { it.orderId.isNotBlank() }
        if (unique.isEmpty()) return
        reconciliationCount += unique.size
        unique.forEach { purchase ->
            scope.launch {
                try {
                    val deadline = System.nanoTime() + 600L * 1_000_000_000L
                    while (System.nanoTime() < deadline) {
                        when (runCatching { classifyTopUpPayment(gateway.storePaymentStatus(purchase.orderId)) }.getOrNull()) {
                            TopUpPaymentOutcome.Success -> {
                                markRestoredSuccess(purchase)
                                return@launch
                            }
                            TopUpPaymentOutcome.Failed -> return@launch
                            else -> delay(3_000L)
                        }
                    }
                } finally {
                    reconciliationCount = (reconciliationCount - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private fun startPolling(
        orderId: String,
        channelCode: String,
        openMode: String,
        intervalSeconds: Int,
        maxSeconds: Int,
    ) {
        pollingJob?.cancel()
        val serial = requestSerial
        pollingJob = scope.launch {
            val interval = intervalSeconds.coerceAtLeast(2)
            val maximum = maxSeconds.takeIf { it > 0 } ?: 600
            val deadline = System.nanoTime() + maximum * 1_000_000_000L
            while (System.nanoTime() < deadline && requestSerial == serial) {
                when (runCatching { classifyTopUpPayment(gateway.storePaymentStatus(orderId)) }.getOrNull()) {
                    TopUpPaymentOutcome.Success -> {
                        if (openMode != "sdk") {
                            runCatching {
                                gateway.reportStorePaymentEvent(orderId, "paid_while_open", channelCode, openMode)
                            }
                        }
                        markSuccess(orderId)
                        return@launch
                    }
                    TopUpPaymentOutcome.Failed -> {
                        emitCurrentPaymentResult("failed", "server_status", "payment_failed", "failed")
                        checkoutState = TopUpCheckoutState.Failed("Payment failed")
                        return@launch
                    }
                    else -> Unit
                }
                delay(interval * 1_000L)
            }
            if (requestSerial == serial) {
                emitCurrentPaymentResult("unknown", "poll_timeout", "poll_timeout", "timeout")
                checkoutState = TopUpCheckoutState.TimedOut
                if (openMode != "sdk") {
                    runCatching { gateway.reportStorePaymentEvent(orderId, "poll_timeout", channelCode, openMode) }
                    runCatching { gateway.reportStorePaymentEvent(orderId, "timeout_cancel", channelCode, openMode) }
                }
            }
        }
    }

    private fun markSuccess(orderId: String) {
        if (!successfulOrderIds.add(orderId)) return
        val route = state
        val values = when (route) {
            is TopUpPurchaseState.OfficialReady -> route.takeIf { it.orderId == orderId }
                ?.let { paymentAnalyticsValues(it, "success", "fulfilled") }
            is TopUpPurchaseState.ThirdPartyReady -> route.takeIf { it.orderId == orderId }
                ?.let { paymentAnalyticsValues(it, "success", "fulfilled") }
            else -> null
        }
        if (values != null) {
            userId()?.let { owner ->
                events.paymentResult(values, owner, "payment:$orderId:terminal")
            }
        }
        checkoutState = TopUpCheckoutState.Succeeded(orderId)
        successSerial += 1
        pollingJob?.cancel()
        refreshJob?.cancel()
    }

    private suspend fun markRestoredSuccess(purchase: RecoveredGooglePurchase) {
        if (purchase.orderId in successfulOrderIds) return
        var owner: String? = null
        for (attempt in 0 until 10) {
            owner = userId()
            if (owner != null) break
            if (attempt < 9) delay(500)
        }
        val identifiedUser = owner ?: return
        if (!successfulOrderIds.add(purchase.orderId)) return
        val values = buildMap<String, Any> {
            put("status", "success")
            put("af_success", "true")
            put("pay_method", "google_play")
            put("payment_flow", "restored")
            put("entry_source", "restored")
            put("af_order_id", purchase.orderId)
            put("stage", "server_status")
            purchase.sdkProductId.takeIf(String::isNotBlank)?.let { put("af_content_id", it) }
        }
        events.paymentResult(values, identifiedUser, "payment:${purchase.orderId}:restored")
    }

    private fun trackPackageClick(productId: String) {
        val product = products[productId]
        events.track(
            name = "click_package",
            parameters = buildMap {
                put("package_id", productId)
                put("entry_source", "main")
                product?.let {
                    val firstBuy = storeUserInfo?.hasMadeFirstPurchase == false && it.isFirstBuyPromotion
                    val price = it.firstBuyPrice.takeIf { value -> firstBuy && value > java.math.BigDecimal.ZERO } ?: it.price
                    put("af_price", price.toDouble())
                    it.currency.takeIf(String::isNotBlank)?.let { currency -> put("af_currency", currency) }
                    put("price_source", "catalog_quote")
                    it.googleProductId.takeIf(String::isNotBlank)?.let { sku -> put("af_content_id", sku) }
                    put("diamond_amount", it.value.toLong())
                    put("bonus_amount", (it.bonusValue + if (firstBuy) it.firstBuyBonusValue else 0).toLong())
                }
            },
            userId = userId(),
        )
    }

    private fun paymentAnalyticsValues(
        route: TopUpPurchaseState.OfficialReady,
        status: String,
        stage: String,
    ): Map<String, Any> = paymentAnalyticsValues(
        productId = route.productId,
        orderId = route.orderId,
        method = route.channelCode,
        flow = paymentFlow,
        status = status,
        stage = stage,
        sdkProductId = route.sdkProductId,
        playQuote = playQuotes[route.orderId],
    )

    private fun paymentAnalyticsValues(
        route: TopUpPurchaseState.ThirdPartyReady,
        status: String,
        stage: String,
    ): Map<String, Any> = paymentAnalyticsValues(
        productId = route.productId,
        orderId = route.orderId,
        method = route.channelCode,
        flow = paymentFlow,
        status = status,
        stage = stage,
        sdkProductId = null,
        playQuote = null,
    )

    private fun paymentAnalyticsValues(
        productId: String,
        orderId: String?,
        method: String,
        flow: TopUpPaymentFlow,
        status: String,
        stage: String,
        sdkProductId: String?,
        playQuote: Pair<Double, String>?,
    ): Map<String, Any> = buildMap {
        put("status", status)
        put("af_success", (status == "success").toString())
        put("pay_method", method)
        put("payment_flow", flow.name.uppercase())
        put("entry_source", "main")
        put("package_id", productId)
        orderId?.takeIf(String::isNotBlank)?.let { put("af_order_id", it) }
        put("stage", stage)
        sdkProductId?.takeIf(String::isNotBlank)?.let { put("af_content_id", it) }
        if (playQuote != null) {
            put("af_price", playQuote.first)
            put("af_currency", playQuote.second)
            put("price_source", "google_play")
        } else products[productId]?.let { product ->
            val firstBuy = storeUserInfo?.hasMadeFirstPurchase == false && product.isFirstBuyPromotion
            val price = product.firstBuyPrice.takeIf { firstBuy && it > java.math.BigDecimal.ZERO } ?: product.price
            put("af_price", price.toDouble())
            product.currency.takeIf(String::isNotBlank)?.let { put("af_currency", it) }
            put("price_source", "catalog_quote")
        }
    }

    private fun emitPaymentResult(
        route: TopUpPurchaseState.OfficialReady,
        status: String,
        stage: String,
        reason: String?,
        keySuffix: String,
    ) {
        val owner = userId() ?: return
        val values = paymentAnalyticsValues(route, status, stage) +
            (reason?.let { mapOf("fail_reason" to it) } ?: emptyMap())
        events.paymentResult(values, owner, "payment:${route.orderId}:$keySuffix")
    }

    private fun emitCurrentPaymentResult(
        status: String,
        stage: String,
        reason: String?,
        keySuffix: String,
    ) {
        val owner = userId() ?: return
        val route = state
        val values = when (route) {
            is TopUpPurchaseState.OfficialReady -> paymentAnalyticsValues(route, status, stage)
            is TopUpPurchaseState.ThirdPartyReady -> paymentAnalyticsValues(route, status, stage)
            else -> return
        } + (reason?.let { mapOf("fail_reason" to it) } ?: emptyMap())
        val orderId = when (route) {
            is TopUpPurchaseState.OfficialReady -> route.orderId
            is TopUpPurchaseState.ThirdPartyReady -> route.orderId
        }
        events.paymentResult(values, owner, "payment:$orderId:$keySuffix")
    }

    private fun emitPreparationFailure(
        productId: String,
        order: StoreOrder?,
        stage: String,
        serial: Long,
    ) {
        val owner = userId() ?: return
        val values = paymentAnalyticsValues(
            productId = productId,
            orderId = order?.id,
            method = "unknown",
            flow = paymentFlow,
            status = "failed",
            stage = stage,
            sdkProductId = officialProductIds[productId],
            playQuote = null,
        ) + mapOf("fail_reason" to if (stage == "order_creation") "order_unknown" else "initialize_failed")
        events.paymentResult(values, owner, "payment:${order?.id ?: productId}:$stage:$serial")
        if (stage == "initialize") {
            events.track(
                "3rdpayment_link_error",
                values + mapOf("page_name" to "recharge_paywall"),
                owner,
                "payment:${order?.id}:link_error:$serial",
            )
        }
    }

    private fun reportEvent(
        route: TopUpPurchaseState.ThirdPartyReady,
        type: String,
        errorCode: String = "",
        errorMessage: String = "",
    ) {
        scope.launch {
            runCatching {
                gateway.reportStorePaymentEvent(
                    route.orderId, type, route.channelCode, route.openMode,
                    route.paymentUrl, errorCode, errorMessage,
                )
            }
        }
    }
}

internal enum class TopUpPaymentFlow {
    Service,
    Legacy;

    companion object {
        fun fromConfig(value: String?): TopUpPaymentFlow =
            if (value.equals("LEGACY", ignoreCase = true)) Legacy else Service
    }
}

internal sealed interface TopUpCheckoutState {
    data object Idle : TopUpCheckoutState
    data object Launching : TopUpCheckoutState
    data object Closing : TopUpCheckoutState
    data class AwaitingPayment(val pendingStorePurchase: Boolean) : TopUpCheckoutState
    data class Succeeded(val orderId: String) : TopUpCheckoutState
    data class Failed(val message: String, val retryPreparedRoute: Boolean = false) : TopUpCheckoutState
    data object Cancelled : TopUpCheckoutState
    data object TimedOut : TopUpCheckoutState
}

internal class TopUpPaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val billingManager = GooglePlayBillingManager.get(application)
    private val analytics = AchatAnalyticsRuntime.get(application)
    val controller = TopUpPaymentController(
        gateway = createAchatRepository(application),
        paymentFlow = TopUpPaymentFlow.fromConfig(BuildConfig.PAYMENT_FLOW),
        scope = viewModelScope,
        errorMessage = { error ->
            topUpPaymentPrepareUserMessage(
                error.message,
                application.getString(R.string.top_up_prepare_error),
                application.getString(R.string.top_up_payment_channel_unavailable),
            )
        },
        events = analytics,
        userId = analytics::currentUserId,
    )

    private var reconciliationStarted = false

    fun startReconciliation() {
        if (reconciliationStarted) return
        reconciliationStarted = true
        controller.beginReconciliationLookup()
        billingManager.restoreActivePurchases(controller::completeReconciliationLookup)
    }

    fun launchOfficial(activity: android.app.Activity, route: TopUpPurchaseState.OfficialReady) {
        controller.beginOfficialCheckout(route)
        billingManager.launch(activity, route) { result ->
            when (result) {
                is GooglePurchaseResult.Launched -> controller.onGoogleBillingLaunched(
                    route, result.price, result.currency,
                )
                is GooglePurchaseResult.Purchased -> {
                    val recovered = result.recoveredOrderId
                    if (recovered != null && recovered != route.orderId) {
                        controller.reconcileGooglePurchases(listOf(RecoveredGooglePurchase(recovered, pending = false)))
                    } else controller.onGooglePurchaseAccepted(route, pending = false)
                }
                is GooglePurchaseResult.Pending -> {
                    val recovered = result.recoveredOrderId
                    if (recovered != null && recovered != route.orderId) {
                        controller.reconcileGooglePurchases(listOf(RecoveredGooglePurchase(recovered, pending = true)))
                    } else controller.onGooglePurchaseAccepted(route, pending = true)
                }
                GooglePurchaseResult.Cancelled -> controller.onGooglePurchaseCancelled(route)
                is GooglePurchaseResult.Error -> controller.onCheckoutLaunchError(
                    googleBillingUserMessage(
                        result.message,
                        getApplication<Application>().getString(R.string.top_up_google_play_unavailable),
                    ),
                )
            }
        }
    }

}
