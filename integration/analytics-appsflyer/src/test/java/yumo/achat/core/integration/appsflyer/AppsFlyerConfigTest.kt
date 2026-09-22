package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsFlyerConfigTest {
    @Test fun `full sdk logging is allowed only in debug builds`() {
        assertTrue(AppsFlyerConfig("key", true, true, true, true).sdkDebugLogging)
        assertFalse(AppsFlyerConfig("key", true, true, false, true).sdkDebugLogging)
    }
}
