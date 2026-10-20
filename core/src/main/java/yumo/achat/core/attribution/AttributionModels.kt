package yumo.achat.core.attribution

import yumo.achat.core.config.ClientIdentity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class LoginAttribution(
    @SerialName("attribution_source") val attributionSource: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("af_uid") val afUid: String,
    val network: String?, val campaign: String?,
    @SerialName("campaign_id") val campaignId: String?,
    val adgroup: String?, @SerialName("adgroup_id") val adgroupId: String?,
    val creative: String?, @SerialName("creative_id") val creativeId: String?,
    val channel: String?, val country: String?, val platform: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("extra_data") val extraData: JsonObject,
) { override fun toString() = "LoginAttribution(归因标识已隐藏)" }

@Serializable
enum class AttributionResultSource(val wireName: String) {
    APPSFLYER_SDK("appsflyer_sdk"),
    GCD_HTTP("gcd_http"),
    GOOGLE_INSTALL_REFERRER("google_install_referrer"),
}

@Serializable
enum class AttributionStage(val wireName: String) {
    SPLASH("splash"),
    POST_LOGIN("post_login"),
    HOME("home"),
    STARTUP_RECOVERY("startup_recovery"),
}

data class AttributionReportMetadata(
    val reportId: String,
    val startupElapsedMillis: Long,
    val resultIndex: Long,
    val stage: AttributionStage,
    val source: AttributionResultSource,
)

data class AttributionResult(val source: AttributionResultSource, val data: JsonObject)

sealed interface ConversionResult {
    data object Pending : ConversionResult
    data class Success(
        val data: JsonObject,
        val source: AttributionResultSource = AttributionResultSource.APPSFLYER_SDK,
    ) : ConversionResult
    data object Failed : ConversionResult
}

interface ConversionSource {
    val conversion: StateFlow<ConversionResult>
    val results: Flow<AttributionResult>
        get() = conversion.mapNotNull { value ->
            (value as? ConversionResult.Success)?.let { AttributionResult(it.source, it.data) }
        }
    fun startAttribution()
    fun retryAttribution() = startAttribution()
    suspend fun verifyHomeAttribution() = Unit
    suspend fun awaitPreHomeConfirmation(): Boolean = false
    val preHomeConfirmation: StateFlow<Boolean>? get() = null
    suspend fun stopAttributionAcquisition(restored: AttributionResult? = null) = Unit
    fun attributionUid(): String
    fun advertisingId(): String?
}
interface AttributionStorage {
    suspend fun readAttribution(): JsonObject?
    suspend fun writeAttribution(data: JsonObject)
}
fun interface LoginAttributionProvider { suspend fun forLogin(): LoginAttribution }
data class AttributionConfiguration(val timeoutMillis: Long, val retryAttempts: Int, val retryIntervalMillis: Long, val cacheWaitMillis: Long = 100,
    val loginTimeoutMillis: Long = 8000, val homeTimeoutMillis: Long = 10000) {
    init { require(timeoutMillis > 0 && retryAttempts > 0 && retryIntervalMillis > 0 && cacheWaitMillis > 0 && loginTimeoutMillis > 0 && homeTimeoutMillis > 0) }
}

/** 缺失或未知状态不是已确认的自然流量。 */
fun JsonObject.hasValidAttribution(): Boolean =
    (get("af_status") as? JsonPrimitive)?.contentOrNull?.lowercase() in setOf("organic", "non-organic")

fun attributionPayload(
    data: JsonObject,
    identity: ClientIdentity,
    deviceId: String,
    afUid: String,
    source: AttributionResultSource = AttributionResultSource.APPSFLYER_SDK,
): LoginAttribution {
    fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    val mediaSource = value("media_source", "network")?.lowercase().orEmpty()
    val metaShaped = value("is_fb")?.equals("true", ignoreCase = true) == true ||
        listOf("facebook", "instagram", "meta ads", "audience network").any(mediaSource::contains) ||
        value("adset", "adset_id") != null
    val loginSource = if (source == AttributionResultSource.GOOGLE_INSTALL_REFERRER) {
        "google_play_install_referrer"
    } else {
        "appsflyer"
    }
    return LoginAttribution(loginSource, deviceId, afUid.ifBlank { value("af_uid").orEmpty() },
        value("media_source", "network"), value("campaign"), value("campaign_id", "af_c_id"),
        if (metaShaped) value("af_adset", "adset") else value("af_adset", "adgroup"),
        if (metaShaped) value("af_adset_id", "adset_id") else value("af_adset_id", "adgroup_id"),
        if (metaShaped) value("af_ad", "adgroup", "creative") else value("af_ad", "creative"),
        if (metaShaped) value("af_ad_id", "adgroup_id", "creative_id") else value("af_ad_id", "creative_id"),
        value("af_channel", "channel"), value("country_code", "country"), "android",
        identity.versionName, identity.packageName, data)
}

/** SDK 回调可以包含布尔、数值和嵌套对象，不全部转成字符串。 */
fun conversionJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to conversionJson(it.value) })
    is Iterable<*> -> JsonArray(value.map(::conversionJson))
    is Array<*> -> JsonArray(value.map(::conversionJson))
    else -> JsonPrimitive(value.toString())
}
