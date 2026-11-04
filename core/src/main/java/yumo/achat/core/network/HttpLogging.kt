package yumo.achat.core.network

import yumo.achat.core.config.CoreRuntimeConfig
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

/** 沿用参考工程的 BODY 日志，脱敏按环境配置选择，统一在 Logcat 的 OkHttp 标签输出。 */
internal fun createHttpLoggingInterceptor(config: CoreRuntimeConfig): HttpLoggingInterceptor =
    createHttpLoggingInterceptor(config.diagnostics.enableDebugLogging, redact = config.diagnostics.redactHttpLogs)

internal fun createHttpLoggingInterceptor(enabled: Boolean,
    sanitize: (String) -> String = ::sanitizeHttpLogMessage, redact: Boolean = true): HttpLoggingInterceptor =
    HttpLoggingInterceptor { message ->
        Timber.tag("OkHttp").d(sanitizeGaidLogMessage(if (redact) sanitize(message) else message))
    }.apply {
        redactHeader("X-GAID")
        redactHeader("X-Advertising-ID")
        if (redact) SENSITIVE_HTTP_HEADERS.forEach(::redactHeader)
        level = if (enabled) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
    }

internal val SENSITIVE_HTTP_HEADERS = setOf(
    "Authorization",
    "Cookie",
    "Set-Cookie",
    "X-AF-UID",
    "X-Device-ID",
    "X-APP-USER-ID",
)

private const val SENSITIVE_HTTP_LOG_FIELD =
    "password|token|refresh_?token|id_?token|access_?token|firebase_?token|fcm_?token|" +
        "device_?id|user_?id|app_?user_?id|advertising_?id|gaid|android_?id|idfa|idfv|" +
        "username|email|secret|api_?key|authorization|af_?uid|character_?id|conversation_?id|" +
        "order_?id|match_?id|matched_?user_?id|product_?id|google_?product_?id|" +
        "latitude|longitude|accuracy|content"

private val SENSITIVE_JSON_LOG_VALUE = Regex(
    pattern = """("(?:$SENSITIVE_HTTP_LOG_FIELD)"\s*:\s*)(?:"(?:\\.|[^"\\])*"|null|true|false|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""",
    option = RegexOption.IGNORE_CASE,
)

private val SENSITIVE_QUERY_LOG_VALUE = Regex(
    pattern = """(^|[?&])((?:$SENSITIVE_HTTP_LOG_FIELD)=)[^&\s]*""",
    option = RegexOption.IGNORE_CASE,
)

private const val AD_SEPARATOR = "(?:[_-]|%5f|%2d)*"
private val AD_FIELD = listOf("gaid", "advertisingid", "googleadvertisingid", "gpsadid")
    .joinToString("|") { alias -> alias.map(Char::toString).joinToString(AD_SEPARATOR) }
private val GAID_JSON_LOG_VALUE = Regex(
    """("(?:$AD_FIELD)"\s*:\s*)(?:"(?:\\.|[^"\\])*"|null|true|false|-?\d+(?:\.\d+)?)""",
    RegexOption.IGNORE_CASE,
)
private val GAID_QUERY_LOG_VALUE = Regex("""(^|[?&])((?:$AD_FIELD)=)[^&\s]*""", RegexOption.IGNORE_CASE)
private val GAID_HEADER_LOG_VALUE = Regex("""(^|\n)((?:x[_-]*)?(?:$AD_FIELD)\s*:\s*)[^\r\n]*""", RegexOption.IGNORE_CASE)
private val UUID_LOG_VALUE = Regex("""(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}""")
private val ESCAPED_QUOTE = Regex("\\\\+\"")

private fun sanitizeGaidLogMessage(message: String): String {
    var body = message
    val quotes = ESCAPED_QUOTE.findAll(message).map { it.value }.distinct().sortedByDescending(String::length)
    for (escapedQuote in quotes) {
        val quote = Regex.escape(escapedQuote)
        val pattern = Regex("""((?<!\\)$quote(?:$AD_FIELD)$quote\s*:\s*)$quote.*?(?<!\\)$quote""", RegexOption.IGNORE_CASE)
        body = pattern.replace(body) { "${it.groupValues[1]}\"[REDACTED]\"" }
    }
    body = GAID_JSON_LOG_VALUE.replace(body) { "${it.groupValues[1]}\"[REDACTED]\"" }
    body = GAID_QUERY_LOG_VALUE.replace(body) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
    body = GAID_HEADER_LOG_VALUE.replace(body) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
    return UUID_LOG_VALUE.replace(body, "[REDACTED]")
}

internal fun sanitizeHttpLogMessage(message: String): String {
    val withoutSensitiveJson = SENSITIVE_JSON_LOG_VALUE.replace(message) { match ->
        "${match.groupValues[1]}\"[REDACTED]\""
    }
    return sanitizeGaidLogMessage(SENSITIVE_QUERY_LOG_VALUE.replace(withoutSensitiveJson) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
    })
}
