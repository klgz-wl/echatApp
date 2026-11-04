package yumo.achat.core.integration.appsflyer

import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class InstallReferrerRaw(
    val installReferrer: String?,
    val referrerClickTimestampSeconds: Long,
    val installBeginTimestampSeconds: Long,
    val referrerClickTimestampServerSeconds: Long = 0,
    val installBeginTimestampServerSeconds: Long = 0,
    val installVersion: String? = null,
    val googlePlayInstant: Boolean = false,
) {
    override fun toString(): String = "InstallReferrerRaw(redacted)"
}

internal fun InstallReferrerRaw.evidenceData(): JsonObject = buildJsonObject {
    put("install_referrer", installReferrer?.let(::JsonPrimitive) ?: JsonNull)
    put("referrer_click_timestamp_seconds", referrerClickTimestampSeconds)
    put("install_begin_timestamp_seconds", installBeginTimestampSeconds)
    put("referrer_click_timestamp_server_seconds", referrerClickTimestampServerSeconds)
    put("install_begin_timestamp_server_seconds", installBeginTimestampServerSeconds)
    put("install_version", installVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("google_play_instant", googlePlayInstant)
}

internal fun encodeInstallReferrerCache(firstInstallTime: Long, raw: InstallReferrerRaw): String {
    require(firstInstallTime > 0)
    return buildJsonObject {
        put("first_install_time_ms", firstInstallTime)
        put("data", raw.evidenceData())
    }.toString()
}

internal fun decodeInstallReferrerCache(
    encoded: String,
    firstInstallTime: Long,
    legacyFirstInstallTime: Long? = null,
): InstallReferrerRaw? = runCatching {
    if (firstInstallTime <= 0) return@runCatching null
    val root = Json.parseToJsonElement(encoded) as? JsonObject ?: return@runCatching null
    if ("first_install_time_ms" !in root) {
        if (legacyFirstInstallTime != firstInstallTime) return@runCatching null
        // Older successful_result stored the camel-case raw object with nullable optional fields.
        val legacy = JsonObject(root.filterValues { it != JsonNull })
        return@runCatching Json { ignoreUnknownKeys = true }.decodeFromJsonElement<InstallReferrerRaw>(legacy)
    }
    val install = root["first_install_time_ms"] as? JsonPrimitive ?: return@runCatching null
    if (install.isString || install.longOrNull != firstInstallTime) return@runCatching null
    val data = root["data"] as? JsonObject ?: return@runCatching null
    fun string(key: String): String? = when (val value = data.getValue(key)) {
        JsonNull -> null
        is JsonPrimitive -> { require(value.isString); value.content }
        else -> error("Invalid string")
    }
    fun number(key: String): Long = (data.getValue(key) as JsonPrimitive).let {
        require(!it.isString); requireNotNull(it.longOrNull)
    }
    val instant = data.getValue("google_play_instant") as JsonPrimitive
    require(!instant.isString)
    InstallReferrerRaw(
        string("install_referrer"), number("referrer_click_timestamp_seconds"),
        number("install_begin_timestamp_seconds"), number("referrer_click_timestamp_server_seconds"),
        number("install_begin_timestamp_server_seconds"), string("install_version"),
        requireNotNull(instant.booleanOrNull),
    )
}.getOrNull()

private val invalidReferrerValues = setOf("", "unknown", "null", "undefined", "none", "(none)", "not set", "(not set)")

internal fun installReferrerSupplement(
    raw: InstallReferrerRaw,
    afUid: String,
    paidMediums: Set<String>,
) = parseReferrer(raw.installReferrer.orEmpty())?.let { parameters ->
    if (parameters.values.any { it.distinct().size > 1 }) return@let null
    val gclid = parameters.singleValue("gclid")?.takeUnless(::invalidReferrerValue)
    val source = parameters.singleValue("utm_source")?.takeUnless(::invalidReferrerValue)
    val medium = parameters.singleValue("utm_medium")?.lowercase()?.takeUnless(::invalidReferrerValue)
    val paidUtm = source != null && medium in paidMediums.map(String::lowercase).toSet()
    if (gclid == null && !paidUtm) return@let null
    buildJsonObject {
        put("af_status", "Non-organic")
        put("af_uid", afUid)
        put("media_source", source ?: "google_play_install_referrer")
        source?.let { put("utm_source", it) }
        medium?.let { put("af_channel", it); put("utm_medium", it) }
        parameters.singleValue("utm_campaign")?.takeUnless(::invalidReferrerValue)?.let { put("campaign", it) }
        parameters.singleValue("utm_id")?.takeUnless(::invalidReferrerValue)?.let { put("campaign_id", it) }
        put("install_referrer_evidence", raw.evidenceData())
    }
}

private fun invalidReferrerValue(value: String): Boolean = value.trim().lowercase() in invalidReferrerValues

private fun parseReferrer(raw: String): Map<String, List<String>>? = runCatching {
    if (raw.isBlank()) return@runCatching emptyMap()
    raw.split('&').fold(linkedMapOf<String, MutableList<String>>()) { result, part ->
        val pieces = part.split('=', limit = 2)
        val key = URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
        val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
        result.apply { getOrPut(key) { mutableListOf() } += value }
    }
}.getOrNull()

private fun Map<String, List<String>>.singleValue(key: String): String? =
    get(key)?.distinct()?.singleOrNull()?.trim()?.takeIf(String::isNotBlank)
