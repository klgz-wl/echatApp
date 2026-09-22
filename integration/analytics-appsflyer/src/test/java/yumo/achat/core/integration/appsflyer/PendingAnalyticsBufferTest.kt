package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAnalyticsBufferTest {
    @Test fun `identity and events survive the foreground wait`() {
        val buffer = PendingAnalyticsBuffer()
        buffer.identify("user-1")
        buffer.event("opened", mapOf("source" to "home"))

        val snapshot = buffer.drain()

        assertTrue(snapshot.identityWasSet)
        assertEquals("user-1", snapshot.identity)
        assertEquals(listOf("opened"), snapshot.events.map { it.name })
        assertTrue(buffer.drain().events.isEmpty())
    }

    @Test fun `explicit logout is retained while waiting`() {
        val buffer = PendingAnalyticsBuffer()
        buffer.identify(null)
        val snapshot = buffer.drain()
        assertTrue(snapshot.identityWasSet)
        assertNull(snapshot.identity)
    }

    @Test fun `bounded buffer drops the oldest event`() {
        val buffer = PendingAnalyticsBuffer(capacity = 2)
        buffer.event("one", emptyMap())
        buffer.event("two", emptyMap())
        buffer.event("three", emptyMap())
        assertEquals(listOf("two", "three"), buffer.drain().events.map { it.name })
    }

    @Test fun `each event freezes the identity active when it was queued`() {
        val buffer = PendingAnalyticsBuffer()
        buffer.identify("user-a")
        buffer.event("first", emptyMap())
        buffer.identify("user-b")
        buffer.event("second", emptyMap())
        buffer.identify(null)
        buffer.event("third", emptyMap())

        val snapshot = buffer.drain()

        assertEquals(listOf("user-a", "user-b", null), snapshot.events.map { it.identity })
        assertEquals(listOf(true, true, true), snapshot.events.map { it.identityWasSet })
        assertTrue(snapshot.identityWasSet)
        assertNull(snapshot.identity)
    }
}
