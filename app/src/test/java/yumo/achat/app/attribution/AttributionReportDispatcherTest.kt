package yumo.achat.app.attribution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import yumo.achat.core.backend.AuthSession

class AttributionReportDispatcherTest {
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

    private fun session(userId: String) = AuthSession(
        userId = userId,
        token = "token",
        refreshToken = "refresh",
        sessionId = "session",
        isAnonymous = true,
    )
}
