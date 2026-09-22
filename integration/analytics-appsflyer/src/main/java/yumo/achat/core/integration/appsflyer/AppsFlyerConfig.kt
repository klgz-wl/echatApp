package yumo.achat.core.integration.appsflyer

data class AppsFlyerConfig(
    val devKey: String,
    val debugLogging: Boolean,
    val diagnosticLogging: Boolean,
    val debugBuild: Boolean,
    val enabled: Boolean,
) {
    init {
        require(devKey.isNotBlank()) { "AppsFlyer dev key must not be blank" }
    }

    val sdkDebugLogging: Boolean
        get() = debugBuild && debugLogging
}
