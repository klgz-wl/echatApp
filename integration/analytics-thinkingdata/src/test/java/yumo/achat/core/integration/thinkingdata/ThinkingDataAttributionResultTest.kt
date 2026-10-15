package yumo.achat.core.integration.thinkingdata

import org.junit.Assert.*
import org.junit.Test

class ThinkingDataAttributionResultTest {
    @Test fun `success is direct s event with only approved manual fields`() {
        val result = ThinkingDataAttributionResult(1, 3, "2.4.0", attributionStatus = "non_organic")
        val event = result.toEvent()
        assertEquals("s_attribution_success", event.name)
        assertEquals(mapOf(
            "callback_id" to 1L,
            "request_attempt" to 3L,
            "app_version" to "2.4.0",
            "platform" to "android",
            "attribution_status" to "non_organic",
        ), event.parameters)
    }
    @Test fun `failure has controlled reason without SDK error`() {
        val event = ThinkingDataAttributionResult(2, 4, "2.4.0", failureReason = "invalid_conversion").toEvent()
        assertEquals("s_attribution_failure", event.name)
        assertEquals("invalid_conversion", event.parameters["failure_reason"])
        assertFalse(event.parameters.containsKey("user_id"))
        assertFalse(event.parameters.containsKey("event_id"))
    }
}
