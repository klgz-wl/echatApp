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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun `backend request survives failed worker and is replayed by recreated sink`() {
        val store = MemoryBackendRequestStore()
        val rejectedApi = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest) = Response.success(ApiResponse<Unit>(1))
        }
        BackendHttpAnalyticsSink(
            api = rejectedApi,
            identity = ClientIdentity("test.package", "1", 30),
            deviceId = { "device" },
            store = store,
            retryDelay = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).apply {
            initialize()
            identify("user-1")
            event("generate_result", mapOf("event_time" to 1L))
        }
        assertEquals(1, store.read().size)

        val accepted = AtomicInteger()
        val acceptedApi = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                accepted.incrementAndGet()
                return Response.success(ApiResponse<Unit>(0))
            }
        }
        BackendHttpAnalyticsSink(
            api = acceptedApi,
            identity = ClientIdentity("test.package", "1", 30),
            deviceId = { "device" },
            store = store,
            retryDelay = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).initialize()

        assertEquals(1, accepted.get())
        assertEquals(0, store.read().size)
    }

    @Test
    fun `poison backend request does not block a later valid request`() {
        val store = MemoryBackendRequestStore()
        val acceptedNames = mutableListOf<String>()
        val api = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                if (request.eventType.endsWith("poison")) return Response.success(ApiResponse<Unit>(1))
                acceptedNames += request.eventType
                return Response.success(ApiResponse<Unit>(0))
            }
        }
        val sink = BackendHttpAnalyticsSink(
            api = api,
            identity = ClientIdentity("test.package", "1", 30),
            deviceId = { "device" },
            store = store,
            retryDelay = {},
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        sink.initialize()

        sink.event("poison", emptyMap())
        sink.event("valid", emptyMap())

        assertEquals(listOf("z_valid"), acceptedNames)
        assertEquals(listOf("z_poison"), store.read().map { it.eventType })
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

    @Test
    fun `terminal dedupe is not persisted when any sink rejects the event`() {
        val store = RecordingDedupeStore()
        val rejected = CountDownLatch(1)
        val hub = AppAnalyticsHub(
            BusinessEventQueue(8, dedupeStore = store),
            listOf(object : AnalyticsSink {
                override fun initialize() = Unit
                override fun identify(userId: String?) = Unit
                override fun event(name: String, parameters: Map<String, Any>) {
                    rejected.countDown()
                    error("sink rejected")
                }
            }),
        )

        hub.track("generate_result", userId = "user-1", onceKey = "task-1")
        assertTrue(rejected.await(1, TimeUnit.SECONDS))

        val retryQueue = BusinessEventQueue(8, dedupeStore = store)
        retryQueue.track("generate_result", userId = "user-1", onceKey = "task-1")
        assertEquals("generate_result", kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(1_000) { retryQueue.events.first().name }
        })
    }

    @Test
    fun `attribution state flow reaches firebase campaign sink`() {
        val event = CountDownLatch(1)
        val client = object : FirebaseClient {
            override fun initialize() = Unit
            override fun identify(userId: String?) = Unit
            override fun setUserProperty(name: String, value: String?) = Unit
            override fun event(name: String, parameters: Map<String, Any>) {
                if (name == "campaign_details") event.countDown()
            }
        }
        val sink = FirebaseAnalyticsSink(client)
        val snapshots = MutableStateFlow<JsonObject?>(null)
        val hub = AppAnalyticsHub(
            BusinessEventQueue(8),
            listOf(sink),
            attributionSnapshots = snapshots,
            campaignAttributionSink = sink,
        )
        hub.initialize()

        snapshots.value = buildJsonObject { put("af_status", "Organic") }

        assertTrue(event.await(1, TimeUnit.SECONDS))
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

    private class RecordingDedupeStore : EventDedupeStore {
        private var values = emptyList<String>()
        override fun read(): List<String> = values
        override fun write(values: List<String>) { this.values = values }
    }
}
