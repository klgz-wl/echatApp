package yumo.achat.core.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 业务层只提交事件，不依赖 SDK；未知值省略，不传凭据、媒体地址或服务端错误原文。 */
interface EventTracker {
    fun track(name: String, parameters: Map<String, Any> = emptyMap(), userId: String? = null,
        onceKey: String? = null)
}
enum class AnalyticsPlatform(val prefix: String) { FIREBASE("f"), APPS_FLYER("a"), THINKING_DATA("s"), BACKEND("z");
    fun eventName(name: String) = "${prefix}_$name"
}
object NoOpEventTracker : EventTracker {
    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) = Unit
}
data class AnalyticsConfiguration(val storageName: String, val backgroundTimeoutMs: Long, val queueCapacity: Int, val dedupeLimit: Int) {
    init { require(storageName.isNotBlank() && backgroundTimeoutMs > 0 && queueCapacity > 0 && dedupeLimit > 0) }
}

fun yumo.achat.core.catalog.Template.analyticsProperties() = mapOf<String, Any>(
    "template_id" to id, "category_id" to categories.firstOrNull().orEmpty(), "modality" to mediaKind.name.lowercase(java.util.Locale.ROOT))

/** Firebase 不接收布尔参数；四端统一用 1/0，保留数值类型且限制到合法标量。 */
fun analyticsParameters(values: Map<String, Any>): Map<String, Any> = values.mapNotNull { (key, value) ->
    if (!Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}").matches(key)) return@mapNotNull null
    val normalized: Any = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> (value as Number).toDouble().takeIf { it.isFinite() } ?: return@mapNotNull null
        is String -> value.take(100)
        else -> return@mapNotNull null
    }
    key to normalized
}.toMap()

/** 四端共用 AppsFlyer 官方支付字段；来源字段明确区分报价和 Play 商品价。 */
fun paymentPriceProperties(price: Double?, currency: String?, source: String): Map<String, Any> = buildMap {
    price?.takeIf { it.isFinite() && it >= 0 }?.let { put("af_price", it); put("price_source", source) }
    currency?.trim()?.uppercase(java.util.Locale.ROOT)?.takeIf { Regex("[A-Z]{3}").matches(it) }
        ?.let { put("af_currency", it) }
}

fun paymentProperties(status: String, method: String, flow: String, source: String, productId: String,
    orderId: String? = null, stage: String, reason: String? = null, amount: Double? = null, currency: String? = null): Map<String, Any> = buildMap {
    put("status", status); put("af_success", (status == "success").toString()); put("pay_method", method); put("payment_flow", flow)
    put("entry_source", source); put("package_id", productId); put("stage", stage)
    orderId?.let { put("af_order_id", it) }; reason?.let { put("fail_reason", it) }
    putAll(paymentPriceProperties(amount, currency, "google_play"))
}

fun paymentRechargeProperties(values: Map<String, Any>, userId: String?): Map<String, Any> = buildMap {
    put("af_content_type", "recharge")
    userId?.takeIf { it.isNotBlank() }?.let { put("self_user_id", it) }
    values["entry_source"]?.let { put("trigger", it) }
    values["fail_reason"]?.let { put("failed_reason", it) }
}

/** 公共业务出口最终参数白名单；未知金额仍保留成功事件。 */
fun businessRevenueParameters(name: String, values: Map<String, Any>): Map<String, Any> {
    if (name != "purchase_success") return values - "af_revenue"
    val revenue = (values["af_revenue"] as? Number)?.toDouble()
    val price = (values["af_price"] as? Number)?.toDouble()
    val currency = values["af_currency"] as? String
    val play = values["pay_method"] == "google_play" && values["price_source"] == "google_play" &&
        values["stage"] in setOf("play_consumed", "fulfilled") && currency?.matches(Regex("[A-Z]{3}")) == true
    val thirdParty = values["pay_method"] != "google_play" && values["price_source"] == "catalog_quote" &&
        values["stage"] == "fulfilled" && currency == "USD"
    return if (values["status"] == "success" && (play || thirdParty) && revenue != null &&
        revenue.isFinite() && revenue >= 0 && revenue == price) values else values - "af_revenue"
}

fun EventTracker.purchaseSuccess(values: Map<String, Any>, userId: String, onceKey: String? = null) {
    val shared = values - "af_revenue"
    val price = (values["af_price"] as? Number)?.toDouble()
    val currency = values["af_currency"] as? String
    val purchaseValues = buildMap {
        putAll(shared)
        putAll(paymentRechargeProperties(values, userId))
        val trustedPrice = (values["pay_method"] == "google_play" && values["price_source"] == "google_play" &&
            currency?.matches(Regex("[A-Z]{3}")) == true) ||
            (values["pay_method"] != "google_play" && values["price_source"] == "catalog_quote" && currency == "USD")
        if (values["status"] == "success" && trustedPrice && price != null && price.isFinite() && price >= 0) {
            put("af_revenue", price)
        }
    }
    track("purchase_success", purchaseValues, userId, onceKey)
}

/** Fulfillment has already succeeded here. Finish its dedupe write on IO even if the caller is cancelled. */
suspend fun EventTracker.purchaseSuccessOnIo(values: Map<String, Any>, userId: String, onceKey: String? = null) {
    try {
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            try { purchaseSuccess(values, userId, onceKey) }
            catch (_: Exception) { /* Analytics must not change the fulfilled payment result. */ }
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
        // The caller may be cancelled while returning from IO. Fulfillment and dedupe are complete.
    }
}
