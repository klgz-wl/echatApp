package com.vexora.core.network

import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.payment.paymentErrorReason
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.util.concurrent.TimeUnit

internal fun paymentClient(publicClient: OkHttpClient, sessions: SessionCoordinator, api: PublicAuthApi,
    logging: Boolean, timeoutSeconds: Long, json: Json, redact: Boolean = true): OkHttpClient {
    val builder = publicClient.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    builder.interceptors().removeAll { it is HttpLoggingInterceptor || it is SessionInterceptor }
    return builder.addInterceptor(SessionInterceptor(sessions, api, invalidateOnRetriedUnauthorized = false))
        // 在会话拦截器后安装 BODY 日志，首次请求和 401 后的重试均可见。
        .addInterceptor(createHttpLoggingInterceptor(logging, ::sanitizePaymentHttpLogMessage, redact).apply {
            if (redact) listOf("Location", "Content-Location", "Link").forEach(::redactHeader)
        })
        .addInterceptor(paymentDiagnostics(logging, json)).build()
}

/** 保留便于检索的摘要；完整的请求、响应与追踪头由上面的 BODY 日志按环境配置输出。 */
internal fun paymentDiagnostics(enabled: Boolean, json: Json) = Interceptor { chain ->
    val request = chain.request()
    val response = chain.proceed(request)
    if (enabled) {
        val reason = if (response.isSuccessful) null else runCatching {
            response.peekBody(4096).use { paymentErrorReason(it.string(), json) }
        }.getOrNull()
        val operation = request.url.pathSegments.lastOrNull()
            ?.takeIf { it in setOf("initialize", "status", "client-events") } ?: "unknown"
        Timber.tag("OkHttp").d("Payment %s %s -> %d auth_present=%s error=%s", request.method,
            operation, response.code, !request.header("Authorization").isNullOrBlank(), reason ?: if (response.isSuccessful) "none" else "unrecognized")
    }
    response
}

private val PAYMENT_SECRET_VALUE = Regex(
    """("(?:payment_?url|checkout_?url|redirect_?url|return_?url|client_?secret|signature|purchase_?token)"\s*:\s*)(?:"(?:\\.|[^"\\])*"|null)""",
    RegexOption.IGNORE_CASE,
)
private val PAYMENT_ORDER_PATH = Regex("""(/client/payments/)[^/?\s]+(/(?:status|client-events))""")
private val PAYMENT_LOG_URL = Regex("""https?:(?://|\\/\\/)[^\s"<>]+""", RegexOption.IGNORE_CASE)

/** 保留支付 API 地址、状态、错误正文和 trace 头；支付链接及凭据仅在日志副本脱敏。 */
internal fun sanitizePaymentHttpLogMessage(message: String): String {
    val safe = PAYMENT_SECRET_VALUE.replace(sanitizeHttpLogMessage(message)) {
        "${it.groupValues[1]}\"[REDACTED]\""
    }
    return if (safe.startsWith("--> ") || safe.startsWith("<-- ") && !safe.startsWith("<-- HTTP FAILED")) {
        PAYMENT_ORDER_PATH.replace(safe) { "${it.groupValues[1]}[REDACTED]${it.groupValues[2]}" }
    } else PAYMENT_LOG_URL.replace(safe, "[REDACTED_URL]")
}
