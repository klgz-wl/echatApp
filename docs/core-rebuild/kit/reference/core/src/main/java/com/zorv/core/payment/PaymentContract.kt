package com.zorv.core.payment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 支付服务使用独立 data 封装，不沿用业务 API 必填 code 的模型。 */
@Serializable data class PaymentEnvelope<T>(val data: T? = null, val code: Int? = null) {
    fun requireData(): T = data?.takeIf { code == null || code == 0 } ?: throw PaymentFailure.InvalidResponse
}
@Serializable data class InitializePaymentRequest(@SerialName("order_id") val orderId: String)
@Serializable data class PaymentSdkParams(@SerialName("product_id") val productId: String? = null)
@Serializable data class InitializedPayment(
    @SerialName("order_id") val orderId: String,
    @SerialName("channel_type") val channelType: String,
    @SerialName("channel_code") val channelCode: String,
    @SerialName("open_mode") val openMode: String,
    @SerialName("sdk_params") val sdkParams: PaymentSdkParams? = null,
    @SerialName("payment_url") val paymentUrl: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("query_interval_seconds") val queryIntervalSeconds: Int? = null,
    @SerialName("max_query_seconds") val maxQuerySeconds: Int? = null,
) {
    fun validate(expectedOrder: String): InitializedPayment {
        if (orderId != expectedOrder || orderId.isBlank() || channelCode.isBlank()) throw PaymentFailure.InvalidResponse
        when (channelType) {
            "official" -> if (channelCode != "google_play" || openMode != "sdk" || sdkParams?.productId.isNullOrBlank()) throw PaymentFailure.InvalidResponse
            "third_party" -> if (openMode !in setOf("webview", "external_browser") || !isPaymentHttpsUrl(paymentUrl) ||
                paymentTime(expiresAt) == null) throw PaymentFailure.InvalidResponse
            else -> throw PaymentFailure.InvalidResponse
        }
        return this
    }
    override fun toString() = "InitializedPayment(支付链接已隐藏)"
}
@Serializable data class PaymentOrderStatus(
    @SerialName("order_id") val orderId: String,
    val status: String,
    @SerialName("fulfillment_status") val fulfillmentStatus: String? = null,
) {
    val successful get() = status == "paid" && fulfillmentStatus == "fulfilled"
    val terminalFailure get() = status in setOf("failed", "cancelled", "expired")
}
@Serializable data class PaymentClientEvent(
    @SerialName("event_type") val eventType: String,
    @SerialName("channel_code") val channelCode: String? = null,
    @SerialName("open_mode") val openMode: String? = null,
    val page: String = "recharge_paywall",
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val metadata: JsonObject? = null,
)
@Serializable data class PaymentEventReceipt(val recorded: Boolean)

sealed class PaymentFailure : Exception() {
    data object Unavailable : PaymentFailure()
    data object InvalidResponse : PaymentFailure()
    data class Http(val status: Int, val reason: String?) : PaymentFailure() {
        val cannotInitialize get() = status == 404 || (status == 409 && reason == "PAYMENT_ORDER_NOT_INITIALIZABLE")
    }
}

data class PaymentConfiguration(val baseUrl: String, val storageName: String, val intervalSeconds: Int,
    val maxSeconds: Int, val successDisplayMs: Long, val eventLimit: Int, val eventAttempts: Int,
    val eventRetryMs: Long) {
    init {
        require(baseUrl.isEmpty() || (isPaymentHttpsUrl(baseUrl) && baseUrl.endsWith('/') && URI(baseUrl).rawQuery == null && URI(baseUrl).rawFragment == null))
        require(storageName.isNotBlank() && intervalSeconds > 0 && maxSeconds >= intervalSeconds)
        require(successDisplayMs > 0 && eventLimit > 0 && eventAttempts > 0 && eventRetryMs > 0)
    }
    fun interval(value: Int?) = (value?.takeIf { it > 0 } ?: intervalSeconds).coerceIn(intervalSeconds, maxSeconds)
    fun duration(value: Int?) = (value?.takeIf { it > 0 } ?: maxSeconds).coerceAtMost(maxSeconds)
}
fun isPaymentHttpsUrl(value: String?): Boolean = runCatching {
    val uri = URI(value ?: return false)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

/** 使用 API 24 可用的日期类，避免依赖未启用的 java.time desugaring。 */
fun paymentTime(value: String?): Long? {
    if (value == null || !Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})""").matches(value)) return null
    val normalized = value.replace(Regex("""\.(\d+)""")) { "." + it.groupValues[1].padEnd(3, '0').take(3) }
    val pattern = if ('.' in normalized) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX"
    val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    return format.parse(normalized, position)?.time?.takeIf { position.index == normalized.length }
}
fun paymentTimestamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
