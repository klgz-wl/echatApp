package yumo.achat.core.backend

import kotlinx.serialization.json.JsonObject
import yumo.achat.core.attribution.LoginAttribution
import yumo.achat.core.attribution.AdvertisingIdProvider
import yumo.achat.core.attribution.readOrNull

interface BackendAttribution {
    suspend fun forLogin(installDeviceId: String): LoginAttribution
    fun currentId(): String
    suspend fun report(session: AuthSession)
}

class NoOpBackendAttribution(
    private val configuration: AchatBackendConfiguration,
    private val advertisingIds: AdvertisingIdProvider = AdvertisingIdProvider { null },
) : BackendAttribution {
    override suspend fun forLogin(installDeviceId: String): LoginAttribution {
        val gaid = advertisingIds.readOrNull()
        return LoginAttribution(
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
            gaid = gaid,
        )
    }

    override fun currentId(): String = ""
    override suspend fun report(session: AuthSession) = Unit
}
