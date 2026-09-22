package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class AchatBackendConfigTest {
    @Test
    fun `client version matches backend registered app version`() {
        assertEquals("2.0.0", AchatBackendClient.DEFAULT_CLIENT_VERSION)
    }
}
