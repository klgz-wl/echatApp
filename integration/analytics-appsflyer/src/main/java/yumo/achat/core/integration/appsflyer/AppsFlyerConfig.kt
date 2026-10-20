package yumo.achat.core.integration.appsflyer

import yumo.achat.core.attribution.AttributionResult

data class AppsFlyerConfig(
    val devKey: String,
    val debugLogging: Boolean,
    val enabled: Boolean,
    val diagnosticLogging: Boolean,
    val gcd: GcdRecheckConfig,
    val installReferrerTimeoutMillis: Long,
    val installReferrerStorageName: String,
    val installReferrerPaidMediums: Set<String>,
    val restoredAttribution: AttributionResult? = null,
    val canCollect: () -> Boolean,
    val eventQueueCapacity: Int = 64,
    val minSessionIntervalSeconds: Int = 1,
    val requestTimeoutMillis: Long = 15_000,
) {
    init {
        require(devKey.isNotBlank()) { "AppsFlyer dev key must not be blank" }
        require(minSessionIntervalSeconds > 0)
        require(requestTimeoutMillis > 0)
        require(installReferrerTimeoutMillis > 0)
        require(installReferrerStorageName.isNotBlank())
        require(eventQueueCapacity > 0) { "AppsFlyer event queue capacity must be positive" }
    }
}
