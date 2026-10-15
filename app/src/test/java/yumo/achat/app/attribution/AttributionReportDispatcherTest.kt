package yumo.achat.app.attribution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import yumo.achat.core.backend.AuthSession

class AttributionReportDispatcherTest {
    @Test
    fun `real report publishes receipt for current epoch and snapshot`() {
        var now = 41L
        val dispatcher = AttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ -> now = 73L },
            nowNanos = { now },
        )
        val snapshot = buildJsonObject { put("af_status", "Organic") }
        dispatcher.updateSession(session("user-1").copy(epoch = "epoch-one"))
        dispatcher.updateSnapshot(snapshot)

        val receipt = dispatcher.receipts.value
        assertNotNull(receipt)
        assertEquals("epoch-one", receipt?.epoch)
        assertEquals(snapshot, receipt?.snapshot)
        assertEquals(73L, receipt?.reportedAtNanos)
    }

    @Test
    fun `same user with new epoch requires new report`() {
        var reports = 0
        val dispatcher = AttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ -> reports++ },
        )
        val snapshot = buildJsonObject { put("af_status", "Non-organic") }
        dispatcher.updateSnapshot(snapshot)
        dispatcher.updateSession(session("user-1").copy(epoch = "first"))
        dispatcher.updateSession(session("user-1").copy(epoch = "second"))

        assertEquals(2, reports)
        assertEquals("second", dispatcher.receipts.value?.epoch)
    }
    @Test
    fun `report waits for both snapshot and session and dedupes key`() {
        val reports = mutableListOf<String>()
        val reportedUsers = mutableListOf<String>()
        val dispatcher = AttributionReportDispatcher(
            attempts = 3,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { session, data -> reports += "${session.userId}:${data["campaign"]}" },
            onReported = { reportedUsers += it.userId },
        )

        dispatcher.updateSnapshot(buildJsonObject { put("campaign", "spring") })
        assertEquals(emptyList<String>(), reports)
        dispatcher.updateSession(session("user-1"))
        dispatcher.updateSession(session("user-1"))
        dispatcher.updateSnapshot(buildJsonObject { put("campaign", "spring") })

        assertEquals(1, reports.size)
        assertEquals(listOf("user-1"), reportedUsers)
    }

    @Test
    fun `failed report retries within configured bound`() {
        var attempts = 0
        val dispatcher = AttributionReportDispatcher(
            attempts = 3,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ ->
                attempts += 1
                if (attempts < 3) error("offline")
            },
        )

        dispatcher.updateSession(session("user-1"))
        dispatcher.updateSnapshot(buildJsonObject { put("campaign", "spring") })

        assertEquals(3, attempts)
    }

    @Test
    fun `exhausted report does not start another retry batch`() {
        val attempts = AtomicInteger()
        val excessive = CountDownLatch(1)
        val dispatcher = AttributionReportDispatcher(
            attempts = 3,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retryDelay = {},
            report = { _, _ ->
                if (attempts.incrementAndGet() > 3) excessive.countDown()
                error("offline")
            },
        )

        dispatcher.updateSession(session("user-1"))
        dispatcher.updateSnapshot(buildJsonObject { put("campaign", "spring") })

        excessive.await(500, TimeUnit.MILLISECONDS)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `exhausted report retries on explicit foreground opportunity`() {
        var attempts = 0
        var online = false
        val dispatcher = AttributionReportDispatcher(
            attempts = 2,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ ->
                attempts += 1
                if (!online) error("offline")
            },
        )

        dispatcher.updateSession(session("user-1"))
        dispatcher.updateSnapshot(buildJsonObject { put("campaign", "spring") })
        assertEquals(2, attempts)

        online = true
        dispatcher.retryPending()

        assertEquals(3, attempts)
    }

    @Test
    fun `persisted digest is not a current process receipt`() {
        val store = MemoryAttributionDeliveryStore()
        var reports = 0
        fun dispatcher() = AttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ -> reports += 1 },
            deliveryStore = store,
        )
        val snapshot = buildJsonObject { put("campaign", "spring"); put("media_source", "network") }
        val sameSnapshotDifferentOrder = buildJsonObject { put("media_source", "network"); put("campaign", "spring") }

        dispatcher().apply {
            updateSession(session("user-1"))
            updateSnapshot(sameSnapshotDifferentOrder)
        }
        dispatcher().apply {
            updateSession(session("user-1"))
            updateSnapshot(snapshot)
        }

        assertEquals(2, reports)
    }

    @Test
    fun `failed digest persistence does not claim cross process delivery`() {
        val store = object : AttributionDeliveryStore {
            override fun wasReported(digest: String) = false
            override fun markReported(digest: String) = false
        }
        var reports = 0
        fun dispatcher() = AttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            retryDelay = {},
            report = { _, _ -> reports += 1 },
            deliveryStore = store,
        )
        val snapshot = buildJsonObject { put("campaign", "spring") }

        repeat(2) {
            dispatcher().apply {
                updateSession(session("user-1"))
                updateSnapshot(snapshot)
            }
        }

        assertEquals(2, reports)
    }

    private fun session(userId: String) = AuthSession(
        userId = userId,
        token = "token",
        refreshToken = "refresh",
        sessionId = "session",
        isAnonymous = true,
        epoch = "epoch-one",
    )
}
