package yumo.achat.core.integration.thinkingdata

import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class ThinkingDataAttributionDeliveryTest {
    @Test fun `login before callback processing preserves frozen account and original milliseconds`() {
        var account: String? = "later-login"
        val identities = mutableListOf<String?>()
        var sentAccount: String? = null
        var sentTime: Date? = null
        var sentEvent: ThinkingDataAttributionEvent? = null
        val delivery = ThinkingDataAttributionDelivery(
            identify = { account = it; identities += it },
            track = { event, time -> sentAccount = account; sentTime = time; sentEvent = event },
        )

        delivery.deliver(ThinkingDataAttributionResult(1, 2, "2.0.0", attributionStatus = "organic"),
            frozenUserId = "callback-account", eventTime = 1_701_234_567_891L)
        account = "another-login"

        assertEquals(listOf("callback-account"), identities)
        assertEquals("callback-account", sentAccount)
        assertEquals(1_701_234_567_891L, sentTime!!.time)
        assertEquals("s_attribution_success", sentEvent!!.name)
        assertEquals(1L, sentEvent!!.parameters["callback_id"])
        assertFalse(sentEvent!!.parameters.containsKey("event_time"))
        assertFalse(sentEvent!!.parameters.containsKey("user_id"))
    }

    @Test fun `anonymous callback stays anonymous even after login and uses failure timestamp`() {
        var account: String? = "new-login"
        val sent = mutableListOf<Triple<String?, Long, String>>()
        val delivery = ThinkingDataAttributionDelivery(
            identify = { account = it },
            track = { event, time -> sent += Triple(account, time.time, event.name) },
        )

        delivery.deliver(ThinkingDataAttributionResult(2, 3, "1.0.2", failureReason = "invalid_conversion"),
            frozenUserId = null, eventTime = 123L)

        assertEquals(listOf(Triple(null, 123L, "s_attribution_failure")), sent)
    }

    @Test fun `failed delivery is attempted once and cannot reassign the next callback`() {
        val owners = mutableListOf<String?>()
        var attempts = 0
        val delivery = ThinkingDataAttributionDelivery(
            identify = { owners += it },
            track = { _, _ -> attempts++; if (attempts == 1) error("sdk failure") },
        )
        val result = ThinkingDataAttributionResult(1, 1, "2.0.0", failureReason = "conversion_callback_failed")

        assertTrue(runCatching { delivery.deliver(result, "first", 1L) }.isFailure)
        delivery.deliver(result.copy(callbackId = 2), "second", 2L)

        assertEquals(2, attempts)
        assertEquals(listOf("first", "second"), owners)
    }
}
