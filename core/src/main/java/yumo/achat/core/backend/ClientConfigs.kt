package yumo.achat.core.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ClientConfigKeys {
    const val PURCHASE_REPORT = "client.appsflyer.purchase.report"

    fun merged(existing: Collection<String>): List<String> =
        (existing.map(String::trim).filter(String::isNotEmpty) + PURCHASE_REPORT).distinct()
}

data class ClientConfigs(
    val packageName: String,
    val platform: String,
    val configKeys: List<String>,
    val configs: JsonObject,
) {
    fun boolean(key: String): Boolean? = (configs[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.booleanOrNull
}

fun parseClientConfigs(raw: String, expectedPackage: String, expectedPlatform: String): ClientConfigs {
    val envelope = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid client config envelope")
    val code = envelope["code"] as? JsonPrimitive
    check(code != null && !code.isString && code.intOrNull == 0) { "Client config request rejected" }
    val data = envelope["data"] as? JsonObject ?: error("Missing client config data")
    val packageName = (data["package_name"] as? JsonPrimitive)?.contentOrNull
    val platform = (data["platform"] as? JsonPrimitive)?.contentOrNull
    check(packageName == expectedPackage && platform == expectedPlatform) { "Client config identity mismatch" }
    val keys = data["config_keys"] as? JsonArray ?: error("Missing client config keys")
    val requestedKeys = keys.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: error("Invalid client config key") }
    val configs = data["configs"] as? JsonObject ?: error("Missing client configs")
    return ClientConfigs(expectedPackage, expectedPlatform, requestedKeys, configs)
}
