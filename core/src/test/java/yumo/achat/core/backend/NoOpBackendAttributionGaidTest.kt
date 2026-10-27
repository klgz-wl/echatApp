package yumo.achat.core.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.attribution.AdvertisingIdProvider

class NoOpBackendAttributionGaidTest {
    @Test fun `anonymous login can include GAID when AppsFlyer attribution is disabled`() = runBlocking {
        val config = AchatBackendConfiguration.Default
        val attribution = NoOpBackendAttribution(config,
            AdvertisingIdProvider { "12345678-1234-4234-8234-123456789abc" }).forLogin("install-id")
        assertEquals("install-id", attribution.deviceId)
        assertEquals("12345678-1234-4234-8234-123456789abc", attribution.gaid)
        assertEquals("", attribution.afUid)
    }
}
