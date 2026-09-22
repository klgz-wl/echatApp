package yumo.achat.core.backend

data class AchatBackendConfiguration(
    val apiBaseUrl: String,
    val packageName: String,
    val clientVersion: String,
) {
    companion object {
        val Default = AchatBackendConfiguration(
            apiBaseUrl = "https://test.appjoly.com",
            packageName = "yumo.achat.app",
            clientVersion = "2.0.0",
        )
    }
}
