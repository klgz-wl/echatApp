package yumo.achat.core.integration.thinkingdata

import java.net.URI

data class ThinkingDataConfig(
    val appId: String,
    val serverUrl: String,
    val debugMode: Boolean,
    val enabled: Boolean,
) {
    init {
        require(appId.isNotBlank()) { "ThinkingData app ID must not be blank" }
        val uri = runCatching { URI(serverUrl) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "ThinkingData server URL must be a valid HTTPS origin"
        }
    }
}
