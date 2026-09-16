package yumo.achat.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.BuildConfig

class AchatBackendConfigTest {
    @Test
    fun `client version matches backend registered app version`() {
        assertEquals("2.0.0", BuildConfig.ACHAT_CLIENT_VERSION)
    }
}
