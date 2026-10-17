package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class GcdRecheckCoordinatorTest {
    private class Host(var valid: Boolean = true)
    private val organic = Json.parseToJsonElement("""{"af_status":"Organic","iscache":true}""") as JsonObject
    private val media = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"Facebook Ads","is_fb":true}""") as JsonObject

    @Test fun `Organic recheck upgrades shared result with bound UID only on real media`() = runBlocking {
        val pending = CompletableDeferred<JsonObject?>()
        val accepted = mutableListOf<JsonObject>()
        var calls = 0
        val coordinator = coordinator(fetch = { calls++; pending.await() }, onMedia = accepted::add)
        val host = Host()

        coordinator.attach(host)
        coordinator.sdkOrganic()
        coordinator.request()
        assertEquals(1, calls)
        pending.complete(media)
        yield()

        assertEquals(1, accepted.size)
        assertEquals("af-uid", accepted.single().getValue("af_uid").jsonPrimitive.content)
        assertEquals(media.getValue("is_fb"), accepted.single().getValue("is_fb"))
        coordinator.request()
        assertEquals(1, calls)
    }

    @Test fun `HTTP Organic does not publish and next check observes monotonic interval`() = runBlocking {
        var now = 0L
        var calls = 0
        val accepted = mutableListOf<JsonObject>()
        val coordinator = coordinator(
            nowNanos = { now },
            fetch = { if (++calls == 1) organic else media },
            onMedia = accepted::add,
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        coordinator.request()

        assertEquals(1, calls)
        assertTrue(accepted.isEmpty())
        now = 5_000_000_000L
        coordinator.request()
        yield()
        assertEquals(2, calls)
        assertEquals(1, accepted.size)
    }

    @Test fun `pause keeps canceled call occupied until cancellation actually completes`() = runBlocking {
        val cancellationRelease = CompletableDeferred<Unit>()
        var now = 0L
        var calls = 0
        val coordinator = coordinator(nowNanos = { now }, fetch = {
            calls++
            if (calls == 1) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cancellationRelease.await() } }
            } else organic
        })
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        assertEquals(1, calls)

        coordinator.detach(host)
        now = 5_000_000_000L
        coordinator.attach(host)
        coordinator.request()
        assertEquals(1, calls)
        cancellationRelease.complete(Unit)
        yield()
        assertEquals(2, calls)
    }

    @Test fun `UID change gate loss and SDK media reject late HTTP media`() = runBlocking {
        for (invalidate in listOf<(GcdRecheckCoordinator<Host>, Host, () -> Unit) -> Unit>(
            { _, _, changeUid -> changeUid() },
            { coordinator, _, _ -> coordinator.revokeAccess() },
            { coordinator, _, _ -> coordinator.sdkMedia() },
        )) {
            val pending = CompletableDeferred<JsonObject?>()
            val accepted = mutableListOf<JsonObject>()
            var uid = "first-uid"
            val coordinator = coordinator(uid = { uid }, fetch = { pending.await() }, onMedia = accepted::add)
            val host = Host()
            coordinator.attach(host)
            coordinator.sdkOrganic()
            invalidate(coordinator, host) { uid = "later-uid" }
            pending.complete(media)
            yield()
            assertTrue(accepted.isEmpty())
        }
    }

    @Test fun `late pause from old host cannot cancel new foreground request`() = runBlocking {
        val pending = CompletableDeferred<JsonObject?>()
        val accepted = mutableListOf<JsonObject>()
        val coordinator = coordinator(fetch = { pending.await() }, onMedia = accepted::add)
        val old = Host()
        val current = Host()
        coordinator.attach(old)
        coordinator.attach(current)
        coordinator.sdkOrganic()
        coordinator.detach(old)

        pending.complete(media)
        yield()
        assertEquals(1, accepted.size)
    }

    @Test fun `cancel before lazy job dispatch releases the occupied slot`() = runBlocking {
        val dispatcher = QueueDispatcher()
        var now = 0L
        var calls = 0
        val coordinator = coordinator(
            scope = CoroutineScope(SupervisorJob() + dispatcher), nowNanos = { now },
            fetch = { calls++; organic },
        )
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        coordinator.detach(host)
        assertEquals(0, calls)

        coordinator.attach(host)
        dispatcher.runAll()
        coordinator.request()
        dispatcher.runAll()
        assertEquals(1, calls)
    }

    @Test fun `SDK media observed before queued HTTP result prevents its publication`() = runBlocking {
        val pending = CompletableDeferred<JsonObject?>()
        val accepted = mutableListOf<JsonObject>()
        var sdkMediaObserved = false
        val coordinator = GcdRecheckCoordinator<Host>(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            minIntervalMillis = 5_000,
            timeoutMillis = 10_000,
            nowNanos = { 0L },
            uid = { "af-uid" },
            eligible = { !sdkMediaObserved },
            validHost = { it.valid },
            fetch = { pending.await() },
            onMedia = accepted::add,
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()

        sdkMediaObserved = true
        pending.complete(media)
        yield()
        assertTrue(accepted.isEmpty())
    }

    @Test fun `blank UID invalid host and revoked gate prevent requests`() = runBlocking {
        var currentUid = ""
        var calls = 0
        val host = Host(false)
        val coordinator = coordinator(uid = { currentUid }, fetch = { calls++; organic })
        coordinator.attach(host)
        coordinator.sdkOrganic()
        assertEquals(0, calls)

        currentUid = "af-uid"
        coordinator.request()
        assertEquals(0, calls)
        host.valid = true
        coordinator.revokeAccess()
        coordinator.request()
        assertEquals(0, calls)
        coordinator.attach(host)
        assertEquals(1, calls)
    }

    @Test fun `timeout and synchronous failure release slot for later checks`() = runBlocking {
        var now = 0L
        var calls = 0
        val coordinator = coordinator(
            nowNanos = { now }, timeoutMillis = 25,
            fetch = {
                calls++
                when (calls) {
                    1 -> error("synchronous failure")
                    2 -> awaitCancellation()
                    else -> organic
                }
            },
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        assertEquals(1, calls)

        now = 5_000_000_000L
        coordinator.request()
        assertEquals(2, calls)
        delay(60)
        now = 10_000_000_000L
        coordinator.request()
        assertEquals(3, calls)
    }

    private fun coordinator(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        nowNanos: () -> Long = { 0L },
        uid: () -> String = { "af-uid" },
        timeoutMillis: Long = 10_000,
        fetch: suspend (String) -> JsonObject?,
        onMedia: (JsonObject) -> Unit = {},
    ) = GcdRecheckCoordinator<Host>(
        scope = scope,
        minIntervalMillis = 5_000,
        timeoutMillis = timeoutMillis,
        nowNanos = nowNanos,
        uid = uid,
        eligible = { true },
        validHost = { it.valid },
        fetch = fetch,
        onMedia = onMedia,
    )

    private class QueueDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
