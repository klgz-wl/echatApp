package com.vexora.core.analytics

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

fun com.vexora.core.catalog.Template.analyticsProperties() = mapOf<String, Any>(
    "template_id" to id, "category_id" to categories.firstOrNull().orEmpty(), "modality" to mediaKind.name.lowercase(java.util.Locale.ROOT))

/** Firebase 不接收布尔参数；三端统一用 1/0，保留数值类型且限制到合法标量。 */
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
}.take(25).toMap()

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

/** 两个结果事件共享价格字段，仅主成功事件记收入；报价和历史未知金额不推算收入。 */
fun EventTracker.paymentResult(values: Map<String, Any>, userId: String, onceKey: String? = null) {
    val shared = values - "af_revenue"
    track("pay_result", shared, userId, onceKey?.let { "$it:result" })
    val compatibility = buildMap<String, Any> {
        put("af_content_type", "recharge"); put("self_user_id", userId)
        values["entry_source"]?.let { put("trigger", it) }
        values["fail_reason"]?.let { put("failed_reason", it) }
        val price = (values["af_price"] as? Number)?.toDouble()
        if (values["status"] == "success" && values["pay_method"] == "google_play" &&
            values["price_source"] == "google_play" && price != null && price.isFinite() && price >= 0 &&
            (values["af_currency"] as? String)?.matches(Regex("[A-Z]{3}")) == true)
            put("af_revenue", price)
    }
    track("payment_custom", shared + compatibility, userId, onceKey?.let { "$it:custom" })
}
