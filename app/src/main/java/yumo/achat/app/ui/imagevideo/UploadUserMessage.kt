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

private fun parseBackendErrorMessage(rawMessage: String): String? =
    runCatching {
        val root = JSONObject(rawMessage)
        root.optString("message")
    }.getOrNull()
