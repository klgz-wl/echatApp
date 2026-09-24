package yumo.achat.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.analytics.EventTracker

class ForegroundAnalyticsTest {
    @Test
    fun `foreground emits cold then warm launch and cancels pending exit`() {
        val events = RecordingTracker()
        var now = 0L
        val scheduler = FakeScheduler()
        var first = true
        val analytics = ForegroundAnalytics(
            events = events,
            backgroundTimeoutMs = 30_000,
            nowElapsed = { now },
            firstLaunch = { first.also { first = false } },
            newSession = {},
            schedule = scheduler::schedule,
        )

        analytics.foreground(true)
        now = 1_000
        analytics.foreground(false)
        now = 2_000
        analytics.foreground(true)
        scheduler.runPending()

        assertEquals(listOf("cold", "warm"), events.named("app_launch").map { it.parameters["launch_type"] })
        assertEquals(true, events.named("app_launch").first().parameters["is_first_launch"])
        assertEquals(0, events.named("app_exit").size)
    }

    @Test
    fun `background timeout emits exit and starts a new session`() {
        val events = RecordingTracker()
        var now = 100L
        val scheduler = FakeScheduler()
        var newSessions = 0
        val analytics = ForegroundAnalytics(
            events = events,
            backgroundTimeoutMs = 30_000,
            nowElapsed = { now },
            firstLaunch = { false },
            newSession = { newSessions += 1 },
            schedule = scheduler::schedule,
        )

        analytics.foreground(true)
        now = 1_100
        analytics.foreground(false)
        scheduler.runPending()
        now = 2_100
        analytics.foreground(true)

        val exit = events.named("app_exit").single()
        assertEquals(1_000L, exit.parameters["session_duration"])
        assertEquals("background_timeout", exit.parameters["exit_reason"])
        assertEquals(1, newSessions)
    }

    private class FakeScheduler {
        private data class Pending(var cancelled: Boolean, val action: () -> Unit)
        private val pending = mutableListOf<Pending>()
        fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            check(delayMillis > 0)
            val value = Pending(false, action)
            pending += value
            return { value.cancelled = true }
        }
        fun runPending() {
            pending.toList().also { pending.clear() }.filterNot { it.cancelled }.forEach { it.action() }
        }
    }

    private class RecordingTracker : EventTracker {
        data class Event(val name: String, val parameters: Map<String, Any>)
        private val events = mutableListOf<Event>()
        override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
            events += Event(name, parameters)
        }
        fun named(name: String) = events.filter { it.name == name }
    }
}
