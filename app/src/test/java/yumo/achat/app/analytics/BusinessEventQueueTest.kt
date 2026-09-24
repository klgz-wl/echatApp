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
    fun `once key survives queue recreation and remains user scoped`() = runBlocking {
        val store = MemoryDedupeStore()
        val first = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        first.track("generate_result", userId = "user-1", onceKey = "task-1")

        val second = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        second.track("generate_result", userId = "user-1", onceKey = "task-1")
        second.track("generate_result", userId = "user-2", onceKey = "task-1")

        val event = withTimeout(1_000) { second.events.first() }
        assertEquals("user-2", event.userId)
    }

    @Test
    fun `event queued before collector is retained`() = runBlocking {
        val queue = BusinessEventQueue(capacity = 8)

        queue.track("app_launch", userId = "user-1")

        val event = withTimeout(1_000) { queue.events.first() }
        assertEquals("app_launch", event.name)
        assertEquals("user-1", event.userId)
    }

    @Test
    fun `events include frozen session and app mode`() = runBlocking {
        val queue = BusinessEventQueue(capacity = 8)
        queue.sessionId = "session-1"
        queue.mode = "B"

        queue.track("page_view", mapOf("page_name" to "home"), userId = "user-1")
        queue.sessionId = "session-2"
        queue.mode = "A"

        val event = withTimeout(1_000) { queue.events.first() }
        assertEquals("session-1", event.parameters["session_id"])
        assertEquals("B", event.parameters["app_mode"])
    }

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

    private class MemoryDedupeStore : EventDedupeStore {
        private var values = emptyList<String>()
        override fun read(): List<String> = values
        override fun write(values: List<String>) { this.values = values }
    }
}
