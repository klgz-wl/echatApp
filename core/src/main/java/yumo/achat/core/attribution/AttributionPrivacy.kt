package yumo.achat.core.attribution

import kotlinx.serialization.json.*

private val advertisingKeys = setOf("gaid", "advertisingid", "googleadvertisingid", "gpsadid")

fun JsonObject.withoutAdvertisingIdentifiers(): JsonObject = cleanAdvertisingIdentifiers() as JsonObject

private fun JsonElement.cleanAdvertisingIdentifiers(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.filterNot { (key, _) ->
        key.replace("_", "").replace("-", "").lowercase() in advertisingKeys
    }.associate { (key, value) -> key to value.cleanAdvertisingIdentifiers() })
    is JsonArray -> JsonArray(map { it.cleanAdvertisingIdentifiers() })
    else -> this
}
