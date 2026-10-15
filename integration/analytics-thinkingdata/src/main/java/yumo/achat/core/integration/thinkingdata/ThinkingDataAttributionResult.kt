package yumo.achat.core.integration.thinkingdata

/** Dedicated SDK-result envelope; no business queue metadata or raw callback data. */
data class ThinkingDataAttributionResult(
    val callbackId: Long,
    val requestAttempt: Long,
    val appVersion: String,
    val attributionStatus: String? = null,
    val failureReason: String? = null,
) {
    init {
        require((attributionStatus != null) != (failureReason != null))
        require(attributionStatus == null || attributionStatus in setOf("organic", "non_organic"))
        require(failureReason == null || failureReason in setOf("conversion_callback_failed", "invalid_conversion"))
    }

    fun toEvent(): ThinkingDataAttributionEvent {
        val parameters = mutableMapOf<String, Any>(
            "callback_id" to callbackId,
            "request_attempt" to requestAttempt,
            "app_version" to appVersion,
            "platform" to "android",
        )
        attributionStatus?.let { parameters["attribution_status"] = it }
        failureReason?.let { parameters["failure_reason"] = it }
        return ThinkingDataAttributionEvent(
            name = if (attributionStatus != null) "s_attribution_success" else "s_attribution_failure",
            parameters = parameters,
        )
    }
}

data class ThinkingDataAttributionEvent(val name: String, val parameters: Map<String, Any>)
