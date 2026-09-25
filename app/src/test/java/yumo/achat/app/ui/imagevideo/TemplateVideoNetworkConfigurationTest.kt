package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateVideoNetworkConfigurationTest {
    @Test
    fun `slow generated videos use timeouts above Media3 defaults and bounded retries`() {
        val configuration = DefaultTemplateVideoNetworkConfiguration

        assertTrue(configuration.connectTimeoutMs > 8_000)
        assertTrue(configuration.readTimeoutMs > 8_000)
        assertTrue(configuration.readTimeoutMs >= 60_000)
        assertEquals(3, configuration.minimumRetryCount)
    }
}
