package yumo.achat.core.integration.thinkingdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThinkingDataConfigTest {
    @Test fun `accepts a valid https receiver origin`() {
        val config = ThinkingDataConfig("app-id", "https://receiver.example.com", false, true)
        assertEquals("app-id", config.appId)
    }

    @Test fun `rejects receiver urls containing query data`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThinkingDataConfig("app-id", "https://receiver.example.com?token=secret", false, true)
        }
    }

    @Test fun `disabled configuration remains explicit`() {
        val config = ThinkingDataConfig("app-id", "https://receiver.example.com", false, false)
        assertEquals(false, config.enabled)
    }
}
