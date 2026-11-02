package yumo.achat.app.ui.imagevideo

import yumo.achat.app.BuildConfig
import yumo.achat.app.R
import yumo.achat.core.billing.BillingConfiguration
import yumo.achat.core.payment.PaymentConfiguration
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState
import yumo.achat.core.wallet.RechargeStreamConfiguration

internal data class CorePaymentConfiguration(
    val payment: PaymentConfiguration,
    val billing: BillingConfiguration,
)

internal fun corePaymentConfiguration() = CorePaymentConfiguration(
    payment = PaymentConfiguration(
        baseUrl = BuildConfig.PAYMENT_BASE_URL,
        storageName = BuildConfig.PAYMENT_STORAGE_NAME,
        intervalSeconds = BuildConfig.PAYMENT_POLL_INTERVAL_SECONDS,
        maxSeconds = BuildConfig.PAYMENT_POLL_MAX_SECONDS,
        successDisplayMs = BuildConfig.PAYMENT_SUCCESS_DISPLAY_MS.toLong(),
        eventLimit = BuildConfig.PAYMENT_EVENT_LIMIT,
        eventAttempts = BuildConfig.PAYMENT_EVENT_ATTEMPTS,
        eventRetryMs = BuildConfig.PAYMENT_EVENT_RETRY_MS.toLong(),
    ),
    billing = BillingConfiguration(
        storageName = BuildConfig.BILLING_STORAGE_NAME,
        defaultTrigger = BuildConfig.PURCHASE_TRIGGER,
        backendOwnedFulfillment = true,
    ),
)

internal fun coreRechargeStreamConfiguration() = RechargeStreamConfiguration(
    initialRetry = BuildConfig.RECHARGE_RETRY_INITIAL_MS.toLong(),
    maxRetry = BuildConfig.RECHARGE_RETRY_MAX_MS.toLong(),
    firstMessageTimeout = BuildConfig.RECHARGE_FIRST_MESSAGE_TIMEOUT_MS.toLong(),
    idleTimeout = BuildConfig.RECHARGE_IDLE_TIMEOUT_MS.toLong(),
    checkInterval = BuildConfig.RECHARGE_CHECK_INTERVAL_MS.toLong(),
    dedupeWindow = BuildConfig.RECHARGE_DEDUPE_WINDOW_MS.toLong(),
)

internal data class CorePaymentPresentation(
    val locksProductSelection: Boolean,
    val canStartPurchase: Boolean,
    val canRetry: Boolean,
)

internal fun corePaymentPresentation(state: PaymentViewState): CorePaymentPresentation {
    val record = state.record
    val stage = record?.stage
    val hasVisibleError = record?.error != null
    val active = record != null && (record.unresolved || stage in setOf(
        PaymentStage.CREATING,
        PaymentStage.INITIALIZING,
        PaymentStage.OFFICIAL_READY,
        PaymentStage.OFFICIAL_LAUNCHED,
        PaymentStage.CHECKOUT,
        PaymentStage.VERIFYING,
        PaymentStage.AWAITING_FULFILLMENT,
        PaymentStage.UNCERTAIN,
        PaymentStage.OFFICIAL_CONSUMED,
    )) && stage !in setOf(PaymentStage.SUCCESS, PaymentStage.FAILED, PaymentStage.ACCESS_DENIED)
    return CorePaymentPresentation(
        locksProductSelection = active,
        canStartPurchase = !state.busy && !state.storageFailed && !active && (record == null || stage in setOf(
            PaymentStage.FAILED,
            PaymentStage.ACCESS_DENIED,
            PaymentStage.SUCCESS,
        )),
        canRetry = active && !state.busy && !state.storageFailed && (record?.recoveryRequired == true || hasVisibleError ||
            (stage == PaymentStage.CHECKOUT && !state.checkoutVisible) || stage in setOf(
            PaymentStage.INITIALIZING,
            PaymentStage.UNCERTAIN,
            PaymentStage.CLOSED,
            PaymentStage.TIMED_OUT,
        )),
    )
}

internal fun requestsThirdPartyLaunch(previous: PaymentViewState, next: PaymentViewState): Boolean =
    next.checkoutVisible && next.record?.thirdParty == true &&
        (!previous.checkoutVisible || previous.record?.key != next.record?.key || previous.epoch != next.epoch)

internal class ThirdPartyLaunchGate {
    private var claimed: Triple<String, String, Long>? = null

    @Synchronized fun claim(epoch: String, key: String, serial: Long): Boolean {
        val previous = claimed
        if (serial <= 0 || (previous != null && serial <= previous.third)) return false
        claimed = Triple(epoch, key, serial)
        return true
    }
}

/** Owned by the ViewModel controller, so replacing the Activity observer retains departure. */
internal class BrowserReturnController {
    private var pending: Pair<String, String>? = null
    private var leftForBrowser = false

    fun launched(epoch: String, key: String) {
        pending = epoch to key
        leftForBrowser = false
    }

    fun paused() { if (pending != null) leftForBrowser = true }

    fun resumed(epoch: String?): String? {
        val launch = pending ?: return null
        if (launch.first != epoch) { clear(); return null }
        if (!leftForBrowser) return null
        clear()
        return launch.second
    }

    fun clear() { pending = null; leftForBrowser = false }
}

internal fun paymentIssueMessageRes(reason: String?): Int = when (reason) {
    "payment_review_required" -> R.string.top_up_payment_review_required
    "recovery_lookup_failed" -> R.string.top_up_recovery_lookup_failed
    "network_error", "checkout_load_error" -> R.string.top_up_payment_network_error
    "card_declined" -> R.string.top_up_card_declined
    "card_expired" -> R.string.top_up_card_expired
    "card_invalid" -> R.string.top_up_card_invalid
    "insufficient_funds" -> R.string.top_up_card_insufficient_funds
    "payment_expired", "expired_link", "timeout" -> R.string.top_up_payment_timeout
    "order_unknown", "verification_failed", "initialize_failed" -> R.string.top_up_payment_unconfirmed
    else -> R.string.top_up_payment_failed
}

internal fun paymentRetryActionRes(reason: String?, action: String?): Int =
    if (action == "change_card" && reason in setOf("card_declined", "card_expired", "card_invalid", "insufficient_funds")) {
        R.string.top_up_use_another_card
    } else R.string.top_up_try_again
