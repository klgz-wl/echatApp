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

private fun parseBackendErrorMessage(rawMessage: String): String? =
    runCatching {
        val root = JSONObject(rawMessage)
        root.optString("message")
    }.getOrNull()
