package yumo.achat.app.ui.imagevideo

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.billing.BillingRepository
import yumo.achat.core.billing.CoinPurchaseController
import yumo.achat.core.billing.CoinPurchaseState
import yumo.achat.core.billing.CoinPurchaseStatus
import yumo.achat.core.billing.blocksNewCoinPurchase
import yumo.achat.core.backend.AuthSession
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.backend.StoreUserInfo
import yumo.achat.core.network.AuthResponse
import yumo.achat.core.payment.PaymentCoordinator
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState
import yumo.achat.core.payment.PurchaseRouter
import yumo.achat.core.payment.RechargeNotificationRoute
import yumo.achat.core.wallet.CoinProduct
import yumo.achat.core.wallet.RechargeNotifications

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

@HiltViewModel
internal class TopUpPaymentViewModel @Inject constructor(
    private val payments: PaymentCoordinator,
    private val router: PurchaseRouter,
    private val sessions: SessionCoordinator,
    private val billing: BillingRepository,
    private val purchases: CoinPurchaseController,
    private val rechargeNotifications: RechargeNotifications,
) : ViewModel() {
    val controller = CoreTopUpPaymentController(payments, router, sessions, billing, purchases, viewModelScope)

    init {
        viewModelScope.launch {
            rechargeNotifications.events.collect { notice ->
                val isCurrentSession = rechargeNotifications.isCurrent(notice)
                val route = if (isCurrentSession) {
                    router.rechargeNotification(notice.orderId)
                } else {
                    RechargeNotificationRoute.Unknown
                }
                controller.applyRechargeNotice(
                    action = rechargeNoticeAction(
                        isCurrentSession = isCurrentSession && rechargeNotifications.isCurrent(notice),
                        route = route,
                    ),
                    orderId = notice.orderId,
                )
            }
        }
    }

    fun syncSession(session: AuthSession) = controller.syncSession(session)

    fun launchOfficial(activity: Activity, route: TopUpPurchaseState.OfficialReady) {
        val epoch = payments.state.value.epoch ?: return
        viewModelScope.launch { router.launchOfficial(activity, route.record.key, epoch) }
    }

    suspend fun reconcileLegacyWalletTransactions(orderIds: Set<String>): Boolean {
        val orderId = router.reconcileLegacyWalletTransactions(orderIds) ?: return false
        controller.applyRechargeNotice(RechargeNoticeAction.LegacySuccess, orderId)
        return true
    }

    suspend fun hasPendingLegacyOrder(): Boolean = router.hasPendingLegacyOrder()

    fun startReconciliation() = Unit
}

internal class CoreTopUpPaymentController(
    private val payments: PaymentCoordinator,
    private val router: PurchaseRouter,
    private val sessions: SessionCoordinator,
    private val billing: BillingRepository,
    private val purchases: CoinPurchaseController,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var selectedProductId by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<TopUpPurchaseState>(TopUpPurchaseState.Idle)
        private set
    var checkoutState by mutableStateOf<TopUpCheckoutState>(TopUpCheckoutState.Idle)
        private set
    var successSerial by mutableLongStateOf(0L)
        private set
    var reconciliationCount by mutableIntStateOf(0)
        private set
    var rechargeRefreshSerial by mutableLongStateOf(0L)
        private set
    var productSelectionLocked by mutableStateOf(false)
        private set

    private var products: Map<String, StoreProduct> = emptyMap()
    private var userInfo: StoreUserInfo? = null
    private var coreState: PaymentViewState = PaymentViewState()
    private var purchaseState: CoinPurchaseState = CoinPurchaseState()
    private var lastSuccessKey: String? = null

    init {
        scope.launch {
            payments.state.collect { next ->
                coreState = next
                productSelectionLocked = corePaymentPresentation(next).locksProductSelection ||
                    blocksNewCoinPurchase(purchaseState.status)
                next.record?.productId?.let { selectedProductId = it }
                state = next.toTopUpPurchaseState()
                checkoutState = next.toTopUpCheckoutState(purchaseState)
                val successKey = next.record?.takeIf { it.stage == PaymentStage.SUCCESS }?.key
                if (successKey != null && successKey != lastSuccessKey) {
                    lastSuccessKey = successKey
                    successSerial += 1
                    payments.shown(successKey)
                    scope.launch {
                        delay(payments.configuration.successDisplayMs)
                        payments.acknowledge(successKey)
                    }
                }
            }
        }
        scope.launch {
            purchases.state.collect { next ->
                purchaseState = next
                productSelectionLocked = corePaymentPresentation(coreState).locksProductSelection ||
                    blocksNewCoinPurchase(next.status)
                checkoutState = coreState.toTopUpCheckoutState(next)
            }
        }
    }

    fun syncSession(session: AuthSession) {
        scope.launch {
            sessions.restore()
            val current = sessions.current
            if (current?.userId != session.userId || current.token != session.token || current.refreshToken != session.refreshToken) {
                sessions.saveLogin(AuthResponse(session.token, session.refreshToken, session.userId))
            }
            billing.onForeground()
        }
    }

    fun retainAvailableProducts(products: List<StoreProduct>, userInfo: StoreUserInfo? = null) {
        this.products = products.associateBy(StoreProduct::id)
        this.userInfo = userInfo
        val activeProduct = coreState.record?.takeIf(PaymentRecord::unresolved)?.productId
        selectedProductId = activeProduct ?: selectedProductId?.takeIf(this.products::containsKey) ?: products.firstOrNull()?.id
    }

    fun selectProduct(productId: String?) {
        if (productSelectionLocked) return
        selectedProductId = productId?.takeIf(products::containsKey)
    }

    fun prepare(activity: Activity) {
        scope.launch {
            sessions.synchronize()
            if (blocksNewCoinPurchase(purchaseState.status)) return@launch
            val record = coreState.record
            if (record != null && corePaymentPresentation(coreState).canRetry) {
                payments.retry(record.key)
                return@launch
            }
            if (!corePaymentPresentation(coreState).canStartPurchase) return@launch
            val product = selectedProductId?.let(products::get) ?: return@launch
            router.buy(activity, product.toCoinProduct(userInfo), "main")
        }
    }

    fun openThirdParty(route: TopUpPurchaseState.ThirdPartyReady) = payments.opened(route.record.key)
    fun onThirdPartyPageLoaded(route: TopUpPurchaseState.ThirdPartyReady) = payments.pageEvent(route.record.key, "page_loaded")
    fun onThirdPartyPageError(route: TopUpPurchaseState.ThirdPartyReady, message: String) =
        payments.pageEvent(route.record.key, "page_load_error")
    fun closeThirdParty(route: TopUpPurchaseState.ThirdPartyReady) = payments.close(route.record.key)
    fun refreshPayment(route: TopUpPurchaseState.ThirdPartyReady) = payments.poll(route.record.key)
    fun onCheckoutLaunchError(message: String) {
        coreState.record?.let { record ->
            payments.pageEvent(record.key, "page_load_error")
            if (record.thirdParty) payments.close(record.key)
        }
    }

    fun applyRechargeNotice(action: RechargeNoticeAction, orderId: String?) {
        when (action) {
            RechargeNoticeAction.Ignore -> Unit
            RechargeNoticeAction.RefreshBalance -> rechargeRefreshSerial += 1
            RechargeNoticeAction.LegacySuccess -> {
                purchases.backendFulfilled(sessions.current?.epoch)
                checkoutState = TopUpCheckoutState.Succeeded(orderId.orEmpty())
                successSerial += 1
            }
        }
    }

    fun retryVisibleSuccess() {
        if (shouldRetryLegacySuccess(
                hasCoreRecord = coreState.record != null,
                checkoutSucceeded = checkoutState is TopUpCheckoutState.Succeeded,
            )
        ) {
            successSerial += 1
        }
    }

    fun acknowledgeLegacySuccess() {
        if (coreState.record == null && checkoutState is TopUpCheckoutState.Succeeded) {
            checkoutState = TopUpCheckoutState.Idle
        }
    }
}

private fun StoreProduct.toCoinProduct(userInfo: StoreUserInfo?): CoinProduct {
    val firstBuyEligible = userInfo?.hasMadeFirstPurchase == false && isFirstBuyPromotion
    return CoinProduct(
        id = id,
        type = type,
        name = name,
        price = price.toDouble(),
        currency = currency,
        coins = value.toLong(),
        bonus = bonusValue.toLong(),
        googleProductId = googleProductId.takeIf(String::isNotBlank),
        subscription = isSubscription,
        thirdPartyProductId = thirdPartyProductId.takeIf(String::isNotBlank),
        firstBuyPrice = firstBuyPrice.takeIf { firstBuyEligible }?.toDouble(),
        firstBuyBonus = firstBuyBonusValue.takeIf { firstBuyEligible }?.toLong(),
        firstBuyPromotion = firstBuyEligible,
        originalPrice = price.toDouble(),
    )
}

private fun PaymentViewState.toTopUpPurchaseState(): TopUpPurchaseState {
    if (storageFailed) {
        return TopUpPurchaseState.Error("", "Payment storage is unavailable. Please try again later.")
    }
    val value = record ?: return TopUpPurchaseState.Idle
    return when {
        value.stage in setOf(PaymentStage.CREATING, PaymentStage.INITIALIZING) && value.error == null ->
            TopUpPurchaseState.Preparing(value.productId)
        value.initialized?.channelType == "official" && value.stage in setOf(
            PaymentStage.OFFICIAL_READY,
            PaymentStage.OFFICIAL_LAUNCHED,
            PaymentStage.AWAITING_FULFILLMENT,
            PaymentStage.CLOSED,
            PaymentStage.TIMED_OUT,
            PaymentStage.SUCCESS,
        ) -> TopUpPurchaseState.OfficialReady(value)
        value.thirdParty -> TopUpPurchaseState.ThirdPartyReady(value)
        value.error != null -> TopUpPurchaseState.Error(
            value.productId,
            "Payment is temporarily unavailable. Please try again later.",
        )
        else -> TopUpPurchaseState.Idle
    }
}

private fun PaymentViewState.toTopUpCheckoutState(purchase: CoinPurchaseState): TopUpCheckoutState {
    val value = record ?: return TopUpCheckoutState.Idle
    if (value.initialized?.channelType == "official" && purchase.epoch == epoch &&
        value.stage in setOf(PaymentStage.OFFICIAL_LAUNCHED, PaymentStage.AWAITING_FULFILLMENT)
    ) {
        when (purchase.status) {
            CoinPurchaseStatus.PENDING -> return TopUpCheckoutState.AwaitingPayment(true)
            CoinPurchaseStatus.CANCELLED -> return TopUpCheckoutState.Cancelled
            CoinPurchaseStatus.FAILED -> return TopUpCheckoutState.Failed("Payment failed", retryPreparedRoute = true)
            else -> Unit
        }
    }
    return when (value.stage) {
        PaymentStage.OFFICIAL_LAUNCHED,
        PaymentStage.VERIFYING,
        PaymentStage.AWAITING_FULFILLMENT,
        PaymentStage.OFFICIAL_CONSUMED,
        -> TopUpCheckoutState.AwaitingPayment(false)
        PaymentStage.CHECKOUT -> if (value.openedAt == null) {
            TopUpCheckoutState.Idle
        } else {
            TopUpCheckoutState.AwaitingPayment(false)
        }
        PaymentStage.SUCCESS -> TopUpCheckoutState.Succeeded(value.orderId.orEmpty())
        PaymentStage.FAILED,
        PaymentStage.ACCESS_DENIED,
        -> TopUpCheckoutState.Failed("Payment failed")
        PaymentStage.CLOSED -> TopUpCheckoutState.Cancelled
        PaymentStage.TIMED_OUT -> TopUpCheckoutState.TimedOut
        else -> TopUpCheckoutState.Idle
    }
}
