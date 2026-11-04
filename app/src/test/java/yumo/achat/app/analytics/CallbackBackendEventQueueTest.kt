package yumo.achat.app.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import yumo.achat.core.analytics.EventApi
import yumo.achat.core.analytics.ReportEventRequest
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.network.ApiResponse
import yumo.achat.core.attribution.AdvertisingIdProvider
import yumo.achat.core.integration.appsflyer.AppsFlyerCallbackOwner

class CallbackBackendEventQueueTest {
    @Test fun `durable business outbox cannot replay historical conversion callback records`() {
        val sent = mutableListOf<String>()
        val store = MemoryBackendRequestStore()
        val request = ReportEventRequest("device", "z_attribution_success", 123L, null,
            "test.app", "1", "android", emptyMap())
        store.write(listOf(request, request.copy(eventType = "z_attribution_failure"), request.copy(eventType = "z_page_view")))
        BackendHttpAnalyticsSink(
            api = object : EventApi {
                override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                    sent += request.eventType
                    return Response.success(ApiResponse(0))
                }
            },
            identity = ClientIdentity("test.app", "1", 15),
            deviceId = { "device" },
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).initialize()
        assertEquals(listOf("z_page_view"), sent)
        assertTrue(store.read().isEmpty())
    }

    @Test fun `bounded callback queue drops overflow sends once and keeps ingress ownership`() {
        val sent = mutableListOf<ReportEventRequest>()
        val api = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                sent += request
                return Response.success(ApiResponse(1))
            }
        }
        val queue = CallbackBackendEventQueue(api, ClientIdentity("test.app", "2.0.0", 15),
            capacity = 1, advertisingIds = AdvertisingIdProvider { "12345678-1234-4234-8234-123456789abc" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val values = mutableMapOf<String, Any>("callback_id" to 1L)
        val owner = AppsFlyerCallbackOwner("original-device", "original-user", 123L)
        assertTrue(queue.enqueue("attribution_success", values, owner))
        values["callback_id"] = 99L
        assertFalse(queue.enqueue("attribution_failure", emptyMap(), owner))
        queue.initialize()
        queue.initialize()
        assertEquals(1, sent.size)
        assertEquals("original-device", sent.single().deviceId)
        assertEquals("original-user", sent.single().userId)
        assertEquals(123L, sent.single().eventTime)
        assertEquals("1", sent.single().parameters["callback_id"])
        assertEquals("z_attribution_success", sent.single().eventType)
        assertNotNull(sent.single().gaid)
        assertTrue(queue.enqueue("attribution_failure", emptyMap(), owner.copy(userId = null)))
        assertEquals(2, sent.size)
        assertNull(sent.last().userId)
    }

    @Test fun `transport failure cannot stop later callback delivery or restore old requests`() {
        var attempts = 0
        val api = object : EventApi {
            override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
                attempts++
                error("offline")
            }
        }
        fun newQueue() = CallbackBackendEventQueue(api, ClientIdentity("test.app", "1", 15),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val queue = newQueue()
        queue.initialize()
        repeat(2) { queue.enqueue("attribution_failure", emptyMap(), AppsFlyerCallbackOwner("device", null, 123L)) }
        newQueue().initialize()
        assertEquals(2, attempts)
    }
}
