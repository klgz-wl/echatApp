package yumo.achat.app.ui.imagevideo

import org.json.JSONObject
import org.json.JSONException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.AchatBackendHttpException
import yumo.achat.core.backend.ImagePreparationException

internal fun operationUserMessage(error: Throwable, fallback: String): String = when {
    error is CancellationException -> throw error
    error is IOException || error is JSONException -> fallback
    error is ImagePreparationException && error.cause !is IllegalArgumentException &&
        error.message != "Unable to read selected image" -> fallback
    error is AchatBackendHttpException && !error.responseBody.trimStart().startsWith("{") -> fallback
    else -> apiEnvelopeUserMessage(error.message, fallback)
}

internal fun visualGenerationUserMessage(
    rawMessage: String?,
    fallback: String,
): String {
    return apiEnvelopeUserMessage(rawMessage, fallback)
}

internal fun shouldRouteGenerationErrorToTopUp(rawMessage: String?): Boolean {
    val message = visualGenerationUserMessage(rawMessage, fallback = "").lowercase()
    return message.contains("insufficient") &&
        message.contains("diamond") &&
        message.contains("balance")
}

internal fun generationTopUpRouteDelayMillis(rawMessage: String?): Long? =
    if (shouldRouteGenerationErrorToTopUp(rawMessage)) 1_200L else null

internal fun apiEnvelopeUserMessage(rawMessage: String?, fallback: String): String {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return fallback
    if (message.startsWith("[")) return fallback
    if (!message.startsWith("{")) return message.takeIf(String::isSafeUserMessage) ?: fallback
    return runCatching {
        val root = JSONObject(message)
        (root.opt("message") as? String)?.trim().orEmpty()
            .ifBlank { (root.opt("error") as? String)?.trim().orEmpty() }
            .takeIf { it.isNotBlank() && it.isSafeUserMessage() && !it.startsWith("{") && !it.startsWith("[") }
            ?: fallback
    }.getOrDefault(fallback)
}

private val userMessageMarkupPattern = Regex(
    pattern = """<\s*(?:!doctype|/?[a-z][a-z0-9:_-]*)(?:\s|/?>)""",
    option = RegexOption.IGNORE_CASE,
)

private val technicalMessagePattern = Regex(
    """(?:https?|content|file)://|www\.|(?:java|javax|kotlin|android)\.[\w.]+|bearer\s|(?:access_token|refresh_token|token|password|credential|certificate|trace|stack|user_id|device_id)\s*[:=]|\b(?:\d{1,3}\.){3}\d{1,3}\b|unable to resolve host|failed to connect to|\bHTTP\s+\d{3}\b|(?:^|\s)/(?:storage|data|sdcard|Users|var|tmp|home)/|JSON(?:Object|Array)\[|\b\w+(?:Exception|Error):""",
    RegexOption.IGNORE_CASE,
)

private fun String.isSafeUserMessage(): Boolean =
    length <= 512 &&
        !userMessageMarkupPattern.containsMatchIn(this) &&
        !contains("&lt;", ignoreCase = true) &&
        !technicalMessagePattern.containsMatchIn(this)

internal fun topUpPaymentPrepareUserMessage(
    rawMessage: String?,
    fallback: String,
    channelUnavailable: String,
): String {
    val message = apiEnvelopeUserMessage(rawMessage, fallback)
    return if (
        isPaymentChannelUnavailableMessage(rawMessage) ||
        isPaymentChannelUnavailableMessage(message)
    ) {
        channelUnavailable
    } else {
        message
    }
}

internal fun isPaymentChannelUnavailableMessage(rawMessage: String?): Boolean {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return false
    return message.contains("PAYMENT_CHANNEL_UNAVAILABLE", ignoreCase = true) ||
        message.contains("payment_channel_unavailable", ignoreCase = true)
}

internal fun googleBillingUserMessage(rawMessage: String?, fallback: String): String {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return fallback
    val normalized = message.lowercase()
    return if (
        normalized.contains("service connection") ||
        normalized.contains("disconnected") ||
        normalized.contains("billing service") ||
        normalized.contains("service unavailable")
    ) {
        fallback
    } else {
        message
    }
}
