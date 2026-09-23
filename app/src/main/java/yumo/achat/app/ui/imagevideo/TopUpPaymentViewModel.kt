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
import yumo.achat.app.R
import yumo.achat.core.backend.PreparedStorePayment
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreOrder
import yumo.achat.core.backend.StorePaymentGateway

internal class TopUpPaymentController(
    private val gateway: StorePaymentGateway,
    private val scope: CoroutineScope,
    private val errorMessage: (Throwable) -> String,
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

    fun retainAvailableProducts(products: List<StoreProduct>) {
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
                val initialization = gateway.initializeStorePayment(order.id)
                val preparedState = PreparedStorePayment(order, initialization).toTopUpPurchaseState(productId)
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

    fun onGooglePurchaseAccepted(route: TopUpPurchaseState.OfficialReady, pending: Boolean) {
        if (state != route) return
        checkoutState = TopUpCheckoutState.AwaitingPayment(pending)
        startPolling(route.order.id, route.channelCode, "sdk", 3, 600)
    }

    fun onGooglePurchaseCancelled(route: TopUpPurchaseState.OfficialReady) {
        if (state == route) checkoutState = TopUpCheckoutState.Cancelled
    }

    fun onCheckoutLaunchError(message: String) {
        checkoutState = TopUpCheckoutState.Failed(message, retryPreparedRoute = true)
    }

    fun openThirdParty(route: TopUpPurchaseState.ThirdPartyReady) {
        if (state != route || checkoutState != TopUpCheckoutState.Idle) return
        checkoutState = TopUpCheckoutState.AwaitingPayment(false)
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

    fun onThirdPartyPageLoaded(route: TopUpPurchaseState.ThirdPartyReady) = reportEvent(route, "page_loaded")

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
                TopUpPaymentOutcome.Failed -> checkoutState = TopUpCheckoutState.Failed("Payment failed")
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
                                markSuccess(purchase.orderId)
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
                        checkoutState = TopUpCheckoutState.Failed("Payment failed")
                        return@launch
                    }
                    else -> Unit
                }
                delay(interval * 1_000L)
            }
            if (requestSerial == serial) {
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
        checkoutState = TopUpCheckoutState.Succeeded(orderId)
        successSerial += 1
        pollingJob?.cancel()
        refreshJob?.cancel()
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
    val controller = TopUpPaymentController(
        gateway = createAchatRepository(application),
        scope = viewModelScope,
        errorMessage = { error ->
            topUpPaymentPrepareUserMessage(
                error.message,
                application.getString(R.string.top_up_prepare_error),
                application.getString(R.string.top_up_payment_channel_unavailable),
            )
        },
    )

    init {
        controller.beginReconciliationLookup()
        billingManager.restoreActivePurchases(controller::completeReconciliationLookup)
    }

    fun launchOfficial(activity: android.app.Activity, route: TopUpPurchaseState.OfficialReady) {
        controller.beginOfficialCheckout(route)
        billingManager.launch(activity, route) { result ->
            when (result) {
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
