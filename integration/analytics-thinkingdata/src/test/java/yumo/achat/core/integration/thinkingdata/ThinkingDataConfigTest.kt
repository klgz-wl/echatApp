package yumo.achat.core.integration.thinkingdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ThinkingDataConfigTest {
    @Test
    fun `accepts configured https receiver`() {
        val config = ThinkingDataConfig("app-id", "https://receiver.example.test", debugMode = false)

        assertEquals("app-id", config.appId)
    }

    @Test
    fun `rejects blank app id and non http receiver`() {
        assertThrows(IllegalArgumentException::class.java) {
            ThinkingDataConfig("", "https://receiver.example.test", debugMode = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThinkingDataConfig("app-id", "file:///tmp/events", debugMode = false)
        }
    }
}
