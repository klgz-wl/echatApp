package yumo.achat.app.ui.imagevideo

import yumo.achat.app.BuildConfig
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
    val stage = state.record?.stage
    val active = stage in setOf(
        PaymentStage.CREATING,
        PaymentStage.INITIALIZING,
        PaymentStage.OFFICIAL_READY,
        PaymentStage.OFFICIAL_LAUNCHED,
        PaymentStage.CHECKOUT,
        PaymentStage.VERIFYING,
        PaymentStage.AWAITING_FULFILLMENT,
        PaymentStage.UNCERTAIN,
        PaymentStage.CLOSED,
        PaymentStage.TIMED_OUT,
        PaymentStage.OFFICIAL_CONSUMED,
    )
    return CorePaymentPresentation(
        locksProductSelection = active,
        canStartPurchase = stage == null || stage in setOf(
            PaymentStage.FAILED,
            PaymentStage.ACCESS_DENIED,
            PaymentStage.SUCCESS,
        ),
        canRetry = stage in setOf(
            PaymentStage.INITIALIZING,
            PaymentStage.UNCERTAIN,
            PaymentStage.CLOSED,
            PaymentStage.TIMED_OUT,
        ),
    )
}
