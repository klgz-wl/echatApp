package yumo.achat.core.backend

data class AchatBackendConfiguration(
    val baseUrl: String,
    val packageName: String,
    val clientVersion: String,
) {
    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1:")) {
            "Backend base URL must use HTTPS"
        }
        require(packageName.isNotBlank()) { "Package name is required" }
        require(clientVersion.isNotBlank()) { "Client version is required" }
    }
}
