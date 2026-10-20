package yumo.achat.core.integration.appsflyer

import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class InstallReferrerRaw(
    val installReferrer: String,
    val referrerClickTimestampSeconds: Long,
    val installBeginTimestampSeconds: Long,
    val referrerClickTimestampServerSeconds: Long? = null,
    val installBeginTimestampServerSeconds: Long? = null,
    val installVersion: String? = null,
    val googlePlayInstant: Boolean? = null,
)

private val invalidReferrerValues = setOf("", "unknown", "null", "undefined", "none", "(none)", "not set", "(not set)")

internal fun installReferrerSupplement(
    raw: InstallReferrerRaw,
    afUid: String,
    paidMediums: Set<String>,
) = parseReferrer(raw.installReferrer)?.let { parameters ->
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
        buildJsonObject {
            put("install_referrer", raw.installReferrer)
            put("referrer_click_timestamp_seconds", raw.referrerClickTimestampSeconds)
            put("install_begin_timestamp_seconds", raw.installBeginTimestampSeconds)
            raw.referrerClickTimestampServerSeconds?.let { put("referrer_click_timestamp_server_seconds", it) }
            raw.installBeginTimestampServerSeconds?.let { put("install_begin_timestamp_server_seconds", it) }
            raw.installVersion?.let { put("install_version", it) }
            raw.googlePlayInstant?.let { put("google_play_instant", it) }
        }.also { put("install_referrer_evidence", it) }
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
