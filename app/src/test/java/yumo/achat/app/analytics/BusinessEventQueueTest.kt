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
import org.junit.Assert.assertFalse
import org.junit.Test

class BusinessEventQueueTest {
    @Test fun `historical purchase success key survives restart and prevents another income event`() = runBlocking {
        val store = MemoryDedupeStore()
        val values = mapOf<String, Any>("status" to "success", "stage" to "fulfilled", "pay_method" to "channel",
            "price_source" to "catalog_quote", "af_price" to 2.99, "af_currency" to "USD", "af_revenue" to 2.99)
        val first = BusinessEventQueue(8, dedupeStore = store)
        first.track("purchase_success", values, "user", "payment:order:fulfilled:purchase_success")
        first.confirm(withTimeout(1_000) { first.events.first() }, accepted = true)
        val restarted = BusinessEventQueue(8, dedupeStore = store)
        restarted.track("purchase_success", values, "user", "payment:order:fulfilled:purchase_success")
        restarted.track("purchase_success", values, "other", "payment:order:fulfilled:purchase_success")
        val event = withTimeout(1_000) { restarted.events.first() }
        assertEquals("other", event.userId)
    }
    @Test fun `revenue is stripped from every other business event after enqueue merge`() = runBlocking {
        val queue = BusinessEventQueue(capacity = 8)
        queue.track("initiate_pay", mapOf("af_revenue" to 7.0))
        queue.track("page_view", mapOf("af_revenue" to 7.0))
        queue.track("purchase_success", mapOf("status" to "success", "stage" to "play_consumed",
            "pay_method" to "google_play", "price_source" to "google_play", "af_price" to 7.0,
            "af_currency" to "EUR", "af_revenue" to 7.0))
        val events = withTimeout(1_000) { queue.events.take(3).toList() }
        assertFalse(events[0].parameters.containsKey("af_revenue"))
        assertFalse(events[1].parameters.containsKey("af_revenue"))
        assertEquals(7.0, events[2].parameters["af_revenue"])
    }
    @Test
    fun `once key survives queue recreation and remains user scoped`() = runBlocking {
        val store = MemoryDedupeStore()
        val first = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        first.track("generate_result", userId = "user-1", onceKey = "task-1")
        first.confirm(withTimeout(1_000) { first.events.first() }, accepted = true)

        val second = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        second.track("generate_result", userId = "user-1", onceKey = "task-1")
        second.track("generate_result", userId = "user-2", onceKey = "task-1")

        val event = withTimeout(1_000) { second.events.first() }
        assertEquals("user-2", event.userId)
    }

    @Test
    fun `unconfirmed terminal event remains retryable after queue recreation`() = runBlocking {
        val store = MemoryDedupeStore()
        val first = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        first.track("generate_result", userId = "user-1", onceKey = "task-1")
        withTimeout(1_000) { first.events.first() }

        val second = BusinessEventQueue(capacity = 8, dedupeLimit = 10, dedupeStore = store)
        second.track("generate_result", userId = "user-1", onceKey = "task-1")

        assertEquals("generate_result", withTimeout(1_000) { second.events.first() }.name)
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
