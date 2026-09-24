package yumo.achat.app.analytics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.analytics.EventApi
import yumo.achat.core.analytics.ReportEventRequest
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.network.ApiResponse
import kotlinx.coroutines.awaitCancellation
import retrofit2.Response

class AppAnalyticsHubTest {
    @Test
    fun `backend sink returns without waiting for network`() {
        val sink = BackendHttpAnalyticsSink(
            api = object : EventApi {
                override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> = awaitCancellation()
            },
            identity = ClientIdentity("test.package", "1", 30),
            deviceId = { "device" },
        )
        val call = Thread { sink.event("page_view", mapOf("page_name" to "video")) }.apply {
            isDaemon = true
            start()
        }

        call.join(250)

        assertTrue("backend event must enqueue without blocking the caller", !call.isAlive)
    }

    @Test
    fun `concurrent initialize initializes sink once`() {
        val sink = CountingSink()
        val hub = AppAnalyticsHub(BusinessEventQueue(8), listOf(sink))
        val barrier = CyclicBarrier(16)
        val threads = List(16) {
            Thread {
                barrier.await()
                hub.initialize()
            }.apply { start() }
        }

        threads.forEach(Thread::join)

        assertEquals(1, sink.initializeCount.get())
    }

    @Test
    fun `event tracked before explicit initialize reaches sink`() {
        val sink = CountingSink(expectedEvents = 1)
        val hub = AppAnalyticsHub(
            BusinessEventQueue(8),
            listOf(sink),
            commonParameters = { mapOf("device_id" to "device-1", "app_version" to "1", "platform" to "android") },
        )

        hub.track("page_view", mapOf("page_name" to "video"), "user-1")

        assertTrue(sink.eventLatch.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("page_view"), sink.names)
        assertEquals("device-1", sink.parameters.single()["device_id"])
        assertEquals("user-1", sink.parameters.single()["user_id"])
    }

    private class CountingSink(expectedEvents: Int = 0) : AnalyticsSink {
        val initializeCount = AtomicInteger()
        val eventLatch = CountDownLatch(expectedEvents)
        val names = java.util.Collections.synchronizedList(mutableListOf<String>())
        val parameters = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any>>())
        override fun initialize() {
            initializeCount.incrementAndGet()
            Thread.sleep(10)
        }
        override fun identify(userId: String?) = Unit
        override fun event(name: String, parameters: Map<String, Any>) {
            names += name
            this.parameters += parameters
            eventLatch.countDown()
        }
    }
}
