package yumo.achat.app.analytics

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BusinessEventQueueTest {
    @Test
    fun `events include generated id and event time`() = runBlocking {
        val queue = BusinessEventQueue(capacity = 8)
        val nextEvent = async { withTimeout(1_000) { queue.events.first() } }
        yield()

        queue.track("app_launch", mapOf("is_new" to true), userId = "user-1")

        val event = nextEvent.await()
        assertEquals("app_launch", event.name)
        assertEquals("user-1", event.userId)
        assertEquals(true, event.parameters["is_new"])
        assertNotNull(event.parameters["event_id"])
        assertNotNull(event.parameters["event_time"])
    }

    @Test
    fun `once key is scoped by user`() = runBlocking {
        val queue = BusinessEventQueue(capacity = 8)
        val collector = async {
            withTimeout(1_000) { queue.events.take(2).toList() }
        }
        yield()

        queue.track("app_launch", userId = "user-1", onceKey = "startup")
        queue.track("app_launch", userId = "user-1", onceKey = "startup")
        queue.track("app_launch", userId = "user-2", onceKey = "startup")

        val events = collector.await()
        assertEquals(listOf("user-1", "user-2"), events.map { it.userId })
    }
}
