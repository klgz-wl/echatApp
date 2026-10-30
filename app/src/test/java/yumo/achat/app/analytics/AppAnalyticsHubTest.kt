package yumo.achat.app.analytics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response

class AppAnalyticsHubTest {
    @Test fun `purchase gate suppresses all sinks and reopening only reports new orders`() {
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = RecordingDedupeStore()
        val sinks = List(4) { CountingSink(expectedEvents = 2) }
        val hub = AppAnalyticsHub(BusinessEventQueue(8, dedupeStore = store), sinks,
            purchaseSuccessEnabled = enabled::get)
        val values = mapOf<String, Any>("status" to "success", "stage" to "fulfilled", "pay_method" to "channel",
            "price_source" to "catalog_quote", "af_price" to 2.99, "af_currency" to "USD", "af_revenue" to 2.99)
        hub.track("purchase_success", values, "user", "payment:old:fulfilled:purchase_success")
        assertEquals(1, store.read().size)
        hub.track("initiate_pay", mapOf("stage" to "checkout_open"), "user")
        enabled.set(true)
        hub.track("purchase_success", values, "user", "payment:old:fulfilled:purchase_success")
        hub.track("purchase_success", values, "user", "payment:new:fulfilled:purchase_success")
        sinks.forEach { sink ->
            assertTrue(sink.eventLatch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("initiate_pay", "purchase_success"), sink.names)
            assertEquals(2.99, sink.parameters.last()["af_revenue"])
        }
        val restarted = BusinessEventQueue(8, dedupeStore = store)
        restarted.track("purchase_success", values, "user", "payment:old:fulfilled:purchase_success")
        restarted.track("purchase_success", values, "other", "payment:old:fulfilled:purchase_success")
        assertEquals("other", kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(1_000) { restarted.events.first().userId }
        })
    }
    @Test fun `GAID lookup failure still sends original backend event`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ReportEventRequest>()
        val sent = CountDownLatch(1)
        val sink = BackendHttpAnalyticsSink(
            api = object : EventApi {
                override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                    captured.set(request); sent.countDown(); return Response.success(ApiResponse(0))
                }
            },
            identity = ClientIdentity("test.package", "1", 30), deviceId = { "device" },
            advertisingIds = yumo.achat.core.attribution.AdvertisingIdProvider { error("unavailable") },
        )
        sink.initialize()
        sink.identify("original")
        sink.event("page_view", mapOf("event_time" to 123L))
        assertTrue(sent.await(2, TimeUnit.SECONDS))
        assertEquals("original", captured.get().userId)
        assertEquals(123L, captured.get().eventTime)
        assertNull(captured.get().gaid)
    }
    @Test fun `backend outbox adds GAID asynchronously while keeping original event time and user`() {
        val captured = java.util.concurrent.atomic.AtomicReference<ReportEventRequest>()
        val sent = CountDownLatch(1)
        val store = MemoryBackendRequestStore()
        val sink = BackendHttpAnalyticsSink(
            api = object : EventApi {
                override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                    captured.set(request); sent.countDown()
                    return Response.success(ApiResponse(0))
                }
            },
            identity = ClientIdentity("test.package", "1", 30),
            deviceId = { "install-device" },
            store = store,
            advertisingIds = yumo.achat.core.attribution.AdvertisingIdProvider { "12345678-1234-4234-8234-123456789abc" },
        )
        sink.identify("original-user")
        sink.event("page_view", mapOf("event_time" to 123L))
        assertEquals("original-user", store.read().single().userId)
        assertEquals(123L, store.read().single().eventTime)
        assertNull(store.read().single().gaid)
        sink.identify("later-user")
        sink.initialize()
        assertTrue(sent.await(2, TimeUnit.SECONDS))
        assertEquals("original-user", captured.get().userId)
        assertEquals(123L, captured.get().eventTime)
        assertEquals("12345678-1234-4234-8234-123456789abc", captured.get().gaid)
    }
    @Test fun `four enabled sinks receive one purchase and merged parameters cannot leak revenue elsewhere`() {
        val sinks = List(4) { CountingSink(expectedEvents = 2) }
        val hub = AppAnalyticsHub(BusinessEventQueue(8), sinks,
            purchaseSuccessEnabled = { true },
            commonParameters = { mapOf("af_revenue" to 99.0, "platform" to "android") })
        hub.track("initiate_pay", mapOf("af_revenue" to 8.0), "user")
        hub.track("purchase_success", mapOf("status" to "success", "stage" to "play_consumed",
            "pay_method" to "google_play", "price_source" to "google_play", "af_price" to 2.99,
            "af_revenue" to 2.99, "af_currency" to "USD"), "user",
            "play:order:purchase_success")
        sinks.forEach { sink ->
            assertTrue(sink.eventLatch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("initiate_pay", "purchase_success"), sink.names)
            assertTrue("af_revenue" !in sink.parameters[0])
            assertEquals(2.99, sink.parameters[1]["af_revenue"])
            assertEquals("USD", sink.parameters[1]["af_currency"])
        }
    }
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

    @Test fun `ordinary analytics initialization defers login attribution source`() {
        val attribution = CountingSink()
        val backend = CountingSink()
        val hub = AppAnalyticsHub(
            queue = BusinessEventQueue(8),
            sinks = listOf(attribution, backend),
            deferInitializationFor = { it === attribution },
        )

        hub.initialize()

        assertEquals(0, attribution.initializeCount.get())
        assertEquals(1, backend.initializeCount.get())
    }

    @Test fun `queued event retains device identity from creation time`() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        var deviceId = "old-device"
        val sink = CountingSink(expectedEvents = 1)
        try {
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            scope.launch { release.await() }
            val hub = AppAnalyticsHub(
                queue = BusinessEventQueue(8),
                sinks = listOf(sink),
                commonParameters = { mapOf("device_id" to deviceId) },
                scope = scope,
            )

            hub.track("page_view", emptyMap(), "old-user")
            deviceId = "new-device"
            release.countDown()

            assertTrue(sink.eventLatch.await(2, TimeUnit.SECONDS))
            assertEquals("old-device", sink.parameters.single()["device_id"])
        } finally {
            release.countDown()
            dispatcher.close()
        }
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
