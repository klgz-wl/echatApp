package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.BuildConfig

class AchatBackendConfigTest {
    @Test
    fun `client version matches backend registered app version`() {
        assertEquals("2.0.0", BuildConfig.ACHAT_CLIENT_VERSION)
    }

    @Test
    fun `DEV_REUSE variants use the test backend`() {
        assertEquals("DEV_REUSE", BuildConfig.PROD_CONFIG_STATUS)
        assertEquals("https://test.appjoly.com", BuildConfig.ACHAT_API_BASE_URL)
        assertEquals("wss://test.appjoly.com/connection/websocket", BuildConfig.ACHAT_WS_URL)
    }
}
