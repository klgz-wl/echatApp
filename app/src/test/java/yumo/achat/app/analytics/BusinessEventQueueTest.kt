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
    @Test fun `suppressed success uses durable write rather than asynchronous ordinary write`() = runBlocking {
        val durable = java.util.concurrent.CountDownLatch(1)
        val store = object : EventDedupeStore {
            var persisted = emptyList<String>()
            override fun read(): List<String> = persisted
            override fun write(values: List<String>) = Unit // 模拟 apply 尚未落盘。
            override fun writeDurably(values: List<String>): Boolean {
                persisted = values
                durable.countDown()
                return true
            }
        }
        BusinessEventQueue(8, dedupeStore = store).suppress("user", "play:old:purchase_success")
        org.junit.Assert.assertTrue(durable.await(1, java.util.concurrent.TimeUnit.SECONDS))
        val restarted = BusinessEventQueue(8, dedupeStore = store)
        restarted.track("purchase_success", userId = "user", onceKey = "play:old:purchase_success")
        restarted.track("purchase_success", userId = "other", onceKey = "play:old:purchase_success")
        assertEquals("other", withTimeout(1_000) { restarted.events.first() }.userId)
    }

    @Test fun `suppressed success retries durable failure without affecting caller`() {
        var attempts = 0
        val durable = java.util.concurrent.CountDownLatch(1)
        val store = object : EventDedupeStore {
            override fun read(): List<String> = emptyList()
            override fun write(values: List<String>) = Unit
            override fun writeDurably(values: List<String>): Boolean {
                attempts++
                if (attempts >= 2) durable.countDown()
                return attempts >= 2
            }
        }
        BusinessEventQueue(8, dedupeStore = store).suppress("user", "payment:order:fulfilled:purchase_success")
        org.junit.Assert.assertTrue(durable.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(2, attempts)
    }

    @Test fun `ordinary accepted key cannot be overwritten by older suppressed commit`() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val ordinaryWritten = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : EventDedupeStore {
            @Volatile var persisted = emptyList<String>()
            override fun read(): List<String> = persisted
            override fun write(values: List<String>) {
                persisted = values
                if (values.size >= 2) ordinaryWritten.countDown()
            }
            override fun writeDurably(values: List<String>): Boolean {
                entered.countDown()
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                persisted = values
                return true
            }
        }
        val queue = BusinessEventQueue(8, dedupeStore = store)
        val suppressed = Thread { queue.suppress("user", "play:old:purchase_success") }
        try {
            suppressed.start()
            org.junit.Assert.assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            queue.track("purchase_success", userId = "user", onceKey = "play:new:purchase_success")
            val pending = withTimeout(1_000) { queue.events.first() }
            val confirmed = Thread { queue.confirm(pending, accepted = true) }
            confirmed.start()
            ordinaryWritten.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            release.countDown()
            suppressed.join(2_000)
            confirmed.join(2_000)
            val restarted = BusinessEventQueue(8, dedupeStore = store)
            restarted.track("purchase_success", userId = "user", onceKey = "play:old:purchase_success")
            restarted.track("purchase_success", userId = "user", onceKey = "play:new:purchase_success")
            restarted.track("purchase_success", userId = "other", onceKey = "play:fresh:purchase_success")
            assertEquals("other", withTimeout(1_000) { restarted.events.first() }.userId)
        } finally { release.countDown(); suppressed.join(2_000) }
    }
    @Test fun `suppressed success persists original account order key even if earlier delivery fails`() = runBlocking {
        val store = MemoryDedupeStore()
        val first = BusinessEventQueue(8, dedupeStore = store)
        first.track("purchase_success", userId = "user", onceKey = "play:order:purchase_success")
        val pending = withTimeout(1_000) { first.events.first() }
        first.suppress("user", "play:order:purchase_success")
        first.confirm(pending, accepted = false)
        val restarted = BusinessEventQueue(8, dedupeStore = store)
        restarted.track("purchase_success", userId = "user", onceKey = "play:order:purchase_success")
        restarted.track("purchase_success", userId = "other", onceKey = "play:order:purchase_success")
        assertEquals("other", withTimeout(1_000) { restarted.events.first() }.userId)
    }
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
