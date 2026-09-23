package yumo.achat.app.ui.imagevideo

import org.json.JSONObject

internal fun visualGenerationUserMessage(
    rawMessage: String?,
    fallback: String,
): String {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return fallback

    parseBackendErrorMessage(message)?.let { backendMessage ->
        return backendMessage.takeIf { it.isNotBlank() } ?: fallback
    }

    return message
}

internal fun apiEnvelopeUserMessage(rawMessage: String?, fallback: String): String {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return fallback
    if (!message.startsWith("{")) return message
    return runCatching {
        val root = JSONObject(message)
        root.optString("message").trim()
            .ifBlank { root.optString("error").trim() }
            .ifBlank { fallback }
    }.getOrDefault(fallback)
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

private fun parseBackendErrorMessage(rawMessage: String): String? =
    runCatching {
        val root = JSONObject(rawMessage)
        root.optString("message")
    }.getOrNull()
