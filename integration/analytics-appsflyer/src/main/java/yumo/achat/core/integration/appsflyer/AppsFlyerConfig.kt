package yumo.achat.core.integration.appsflyer

data class AppsFlyerConfig(
    val devKey: String,
    val debugLogging: Boolean,
    val enabled: Boolean,
    val diagnosticLogging: Boolean,
    val eventQueueCapacity: Int = 64,
) {
    init {
        require(devKey.isNotBlank()) { "AppsFlyer dev key must not be blank" }
        require(eventQueueCapacity > 0) { "AppsFlyer event queue capacity must be positive" }
    }
}
