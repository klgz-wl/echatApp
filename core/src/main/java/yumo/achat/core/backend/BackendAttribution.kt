package yumo.achat.core.backend

import kotlinx.serialization.json.JsonObject
import yumo.achat.core.attribution.LoginAttribution

interface BackendAttribution {
    suspend fun forLogin(installDeviceId: String): LoginAttribution
    fun currentId(): String
    suspend fun report(session: AuthSession)
}

internal class NoOpBackendAttribution(
    private val configuration: AchatBackendConfiguration,
) : BackendAttribution {
    override suspend fun forLogin(installDeviceId: String): LoginAttribution =
        LoginAttribution(
            attributionSource = "appsflyer",
            deviceId = installDeviceId,
            afUid = "",
            network = null,
            campaign = null,
            campaignId = null,
            adgroup = null,
            adgroupId = null,
            creative = null,
            creativeId = null,
            channel = null,
            country = null,
            platform = "android",
            appVersion = configuration.clientVersion,
            packageName = configuration.packageName,
            extraData = JsonObject(emptyMap()),
        )

    override fun currentId(): String = ""
    override suspend fun report(session: AuthSession) = Unit
}
