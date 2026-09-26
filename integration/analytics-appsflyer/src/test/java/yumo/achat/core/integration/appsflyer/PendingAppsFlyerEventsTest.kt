package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingAppsFlyerEventsTest {
    @Test
    fun `queued events freeze identity and restore latest identity`() {
        val pending = PendingAppsFlyerEvents(capacity = 3)
        pending.identify("user-a")
        assertTrue(pending.enqueue("first", mapOf("position" to 1L)))
        pending.identify("user-b")
        assertTrue(pending.enqueue("second", emptyMap()))

        val batch = pending.drain()

        assertEquals(listOf("user-a", "user-b"), batch.events.map { it.userId })
        assertEquals(listOf("first", "second"), batch.events.map { it.name })
        assertEquals("user-b", batch.restoreUserId)
    }

    @Test
    fun `queue rejects newest event when capacity is reached`() {
        val pending = PendingAppsFlyerEvents(capacity = 1)

        assertTrue(pending.enqueue("first", emptyMap()))
        assertFalse(pending.enqueue("second", emptyMap()))
        assertEquals(listOf("first"), pending.drain().events.map { it.name })
    }

    @Test
    fun `required enqueue reports overflow to the caller`() {
        val pending = PendingAppsFlyerEvents(capacity = 1)
        pending.enqueueOrThrow("first", emptyMap())

        assertThrows(IllegalStateException::class.java) {
            pending.enqueueOrThrow("second", emptyMap())
        }
    }
}
