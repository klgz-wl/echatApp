package yumo.achat.core.integration.appsflyer

data class AppsFlyerConfig(
    val devKey: String,
    val debugLogging: Boolean,
    val enabled: Boolean,
    val diagnosticLogging: Boolean,
) {
    init {
        require(devKey.isNotBlank()) { "AppsFlyer dev key must not be blank" }
    }
}
