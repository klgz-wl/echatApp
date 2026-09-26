package com.zorv.core.config

import java.net.URI

/**
 * Core 运行时配置契约。具体值由应用层提供，Core 不持有任何品牌或构建变体配置。
 */
data class CoreRuntimeConfig(
    val network: NetworkConfig,
    val storage: StorageConfig,
    val diagnostics: DiagnosticsConfig,
)

data class NetworkConfig(
    val baseUrl: String,
    val streamUrl: String,
    val cdnUrl: String,
) {
    init {
        val parsedBaseUrl = requireValidUrl(name = "baseUrl", value = baseUrl, allowedSchemes = HTTP_SCHEMES)
        require(parsedBaseUrl.rawPath.endsWith('/')) { "baseUrl path must end with /" }
        requireValidUrl(name = "streamUrl", value = streamUrl, allowedSchemes = STREAM_SCHEMES)
        requireValidUrl(name = "cdnUrl", value = cdnUrl, allowedSchemes = HTTP_SCHEMES)
    }

    private fun requireValidUrl(
        name: String,
        value: String,
        allowedSchemes: Set<String>,
    ): URI {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value == value.trim()) { "$name must not contain surrounding whitespace" }

        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("$name must be a valid URL", it) }
        require(uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()) {
            "$name must use ${allowedSchemes.joinToString("/")} with a host"
        }
        require(uri.rawUserInfo == null) { "$name must not contain user info" }
        require(uri.rawQuery == null) { "$name must not contain a query" }
        require(uri.rawFragment == null) { "$name must not contain a fragment" }
        return uri
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        val STREAM_SCHEMES = setOf("http", "https", "ws", "wss")
    }
}

data class StorageConfig(
    val databaseName: String,
    val preferencesName: String,
) {
    init {
        require(databaseName.isNotBlank()) { "databaseName must not be blank" }
        require(preferencesName.isNotBlank()) { "preferencesName must not be blank" }
    }
}

data class DiagnosticsConfig(
    val enableDebugLogging: Boolean,
    val redactHttpLogs: Boolean = true,
)
