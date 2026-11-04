package yumo.achat.core.integration.appsflyer

import java.util.concurrent.atomic.AtomicLong

data class AppsFlyerCallbackOwner(
    val deviceId: String = "",
    val userId: String? = null,
    val eventTime: Long = System.currentTimeMillis(),
) {
    override fun toString(): String = "AppsFlyerCallbackOwner(eventTime=$eventTime)"
}

/** One item per real conversion listener invocation; no SDK error text or attribution payload. */
data class AppsFlyerConversionCallback(
    val callbackId: Long,
    val requestAttempt: Long,
    val attributionStatus: String? = null,
    val failureReason: String? = null,
    val owner: AppsFlyerCallbackOwner = AppsFlyerCallbackOwner(),
)

internal class AppsFlyerConversionCallbackSequencer {
    private val sequence = AtomicLong()

    fun success(status: String?, requestAttempt: Long, owner: AppsFlyerCallbackOwner = AppsFlyerCallbackOwner()): AppsFlyerConversionCallback {
        val normalized = when (status?.lowercase()) {
            "organic" -> "organic"
            "non-organic" -> "non_organic"
            else -> null
        }
        return AppsFlyerConversionCallback(
            callbackId = sequence.incrementAndGet(),
            requestAttempt = requestAttempt,
            attributionStatus = normalized,
            failureReason = if (normalized == null) "invalid_conversion" else null,
            owner = owner,
        )
    }

    fun failure(requestAttempt: Long, owner: AppsFlyerCallbackOwner = AppsFlyerCallbackOwner()): AppsFlyerConversionCallback = AppsFlyerConversionCallback(
        callbackId = sequence.incrementAndGet(),
        requestAttempt = requestAttempt,
        failureReason = "conversion_callback_failed",
        owner = owner,
    )
}
