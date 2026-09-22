package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.BuildConfig

class AchatCoreBackendFactoryTest {
    @Test
    fun `configuration maps app BuildConfig into Core backend`() {
        val configuration = achatBackendConfiguration()

        assertEquals(BuildConfig.ACHAT_API_BASE_URL, configuration.apiBaseUrl)
        assertEquals(BuildConfig.APPLICATION_ID, configuration.packageName)
        assertEquals(BuildConfig.ACHAT_CLIENT_VERSION, configuration.clientVersion)
    }
}
