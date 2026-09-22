package yumo.achat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreBackendBoundaryTest {
    @Test
    fun `app passes backend identity into the independent Core module`() {
        val configuration = createAchatBackendConfiguration(
            baseUrl = "https://test.appjoly.com",
            packageName = "yumo.achat.app",
            clientVersion = "2.0.0",
        )

        assertEquals("https://test.appjoly.com", configuration.baseUrl)
        assertEquals("yumo.achat.app", configuration.packageName)
        assertEquals("2.0.0", configuration.clientVersion)
    }
}
