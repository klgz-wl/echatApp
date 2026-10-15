package yumo.achat.core.integration.appsflyer

import java.util.concurrent.atomic.AtomicLong

/** One item per real conversion listener invocation; no SDK error text or attribution payload. */
data class AppsFlyerConversionCallback(
    val callbackId: Long,
    val requestAttempt: Long,
    val attributionStatus: String? = null,
    val failureReason: String? = null,
)

internal class AppsFlyerConversionCallbackSequencer {
    private val sequence = AtomicLong()

    fun success(status: String?, requestAttempt: Long): AppsFlyerConversionCallback {
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
        )
    }

    fun failure(requestAttempt: Long): AppsFlyerConversionCallback = AppsFlyerConversionCallback(
        callbackId = sequence.incrementAndGet(),
        requestAttempt = requestAttempt,
        failureReason = "conversion_callback_failed",
    )
}
