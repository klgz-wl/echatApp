package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.attribution.AttributionResultSource

class GcdRecheckCoordinatorTest {
    private class Host(var valid: Boolean = true)
    private val organic = Json.parseToJsonElement("""{"af_status":"Organic"}""") as JsonObject
    private val media = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"ads"}""") as JsonObject

    @Test fun `SDK organic gets one pre home GCD and organic result is published`() = runBlocking {
        var calls = 0
        val accepted = mutableListOf<Pair<AttributionResultSource, JsonObject>>()
        val coordinator = coordinator(fetch = { calls++; organic }, onResult = { source, data -> accepted += source to data })
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        coordinator.sdkOrganic()
        yield()
        assertEquals(1, calls)
        assertEquals(AttributionResultSource.GCD_HTTP, accepted.single().first)
        assertTrue(coordinator.preHomeComplete.value)
    }

    @Test fun `home has one independent fallback after pre home organic`() = runBlocking {
        var calls = 0
        val coordinator = coordinator(fetch = { if (++calls == 1) organic else media })
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        coordinator.verifyHome()
        coordinator.verifyHome()
        assertEquals(2, calls)
    }

    @Test fun `GCD organic publishes before referrer supplement`() = runBlocking {
        val supplement = CompletableDeferred<JsonObject?>()
        val accepted = mutableListOf<AttributionResultSource>()
        val coordinator = coordinator(
            fetch = { organic }, referrer = { supplement.await() },
            onResult = { source, _ -> accepted += source },
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        assertEquals(listOf(AttributionResultSource.GCD_HTTP), accepted)
        supplement.complete(media)
        yield()
        assertEquals(listOf(AttributionResultSource.GCD_HTTP, AttributionResultSource.GOOGLE_INSTALL_REFERRER), accepted)
    }

    @Test fun `blank UID does not consume pre home but does consume home opportunity`() = runBlocking {
        var uid = ""
        var calls = 0
        val coordinator = coordinator(uid = { uid }, fetch = { calls++; organic })
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        assertEquals(0, calls)
        uid = "af-uid"
        coordinator.sdkOrganic()
        yield()
        assertEquals(1, calls)
        uid = ""
        coordinator.verifyHome()
        uid = "af-uid"
        coordinator.verifyHome()
        assertEquals(1, calls)
    }

    @Test fun `UID available at home runs pending pre home first and waits for both chains`() = runBlocking {
        var currentUid = ""
        var calls = 0
        var reads = 0
        val confirmations = List(2) { CompletableDeferred<JsonObject?>() }
        val preCompleteAtFetch = mutableListOf<Boolean>()
        lateinit var coordinator: GcdRecheckCoordinator<Host>
        coordinator = coordinator(
            uid = { currentUid },
            fetch = {
                calls++
                preCompleteAtFetch += coordinator.preHomeComplete.value
                organic
            },
            referrer = { confirmations[reads++].await() },
        )
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        assertEquals(0, calls)

        currentUid = "af-uid"
        val verification = async(start = CoroutineStart.UNDISPATCHED) { coordinator.verifyHome() }
        assertEquals(1, calls)
        assertFalse(verification.isCompleted)
        confirmations[0].complete(null)
        yield()
        assertEquals(listOf(false, true), preCompleteAtFetch)
        assertEquals(2, calls)
        assertFalse(verification.isCompleted)

        confirmations[1].complete(null)
        verification.await()
        assertTrue(coordinator.preHomeComplete.value)
        coordinator.verifyHome()
        yield()
        assertEquals(2, calls)
        assertEquals(2, reads)
    }

    @Test fun `home waits for pending pre home started when home request finishes`() = runBlocking {
        var calls = 0
        val responses = List(2) { CompletableDeferred<JsonObject?>() }
        val coordinator = coordinator(fetch = { responses[calls++].await() })
        val host = Host()
        coordinator.attach(host)
        val verification = async(start = CoroutineStart.UNDISPATCHED) { coordinator.verifyHome() }
        coordinator.sdkOrganic()
        responses[0].complete(organic)
        yield()
        assertEquals(2, calls)
        assertFalse(verification.isCompleted)
        responses[1].complete(organic)
        verification.await()
        assertTrue(coordinator.preHomeComplete.value)
        coordinator.verifyHome()
        assertEquals(2, calls)
    }

    @Test fun `cancelled delayed UID home waiter does not cancel or repeat pre home acquisition`() = runBlocking {
        var currentUid = ""
        var calls = 0
        val response = CompletableDeferred<JsonObject?>()
        val coordinator = coordinator(
            uid = { currentUid },
            fetch = { if (++calls == 1) response.await() else organic },
        )
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        currentUid = "af-uid"
        val oldPage = async(start = CoroutineStart.UNDISPATCHED) { coordinator.verifyHome() }
        oldPage.cancelAndJoin()
        assertFalse(coordinator.preHomeComplete.value)
        assertEquals(1, calls)
        val newPage = async(start = CoroutineStart.UNDISPATCHED) { coordinator.verifyHome() }
        response.complete(organic)
        newPage.await()
        assertTrue(coordinator.preHomeComplete.value)
        assertEquals(2, calls)
    }

    @Test fun `SDK media cancels auxiliary result and prevents home query`() = runBlocking {
        val pending = CompletableDeferred<JsonObject?>()
        var calls = 0
        val accepted = mutableListOf<JsonObject>()
        val coordinator = coordinator(fetch = { calls++; pending.await() }, onResult = { _, data -> accepted += data })
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        coordinator.sdkMedia()
        pending.complete(media)
        yield()
        coordinator.verifyHome()
        assertEquals(1, calls)
        assertTrue(accepted.isEmpty())
    }

    @Test fun `UID change and revoked access reject a late GCD response`() = runBlocking {
        for (invalidate in listOf<(GcdRecheckCoordinator<Host>) -> Unit>(
            { uid = "changed" },
            { it.revokeAccess() },
        )) {
            uid = "af-uid"
            val pending = CompletableDeferred<JsonObject?>()
            val accepted = mutableListOf<JsonObject>()
            val coordinator = coordinator(uid = { uid }, fetch = { pending.await() }, onResult = { _, data -> accepted += data })
            coordinator.attach(Host())
            coordinator.sdkOrganic()
            invalidate(coordinator)
            pending.complete(media)
            yield()
            assertTrue(accepted.isEmpty())
        }
    }

    @Test fun `timed out pre home opportunity does not remove the home opportunity`() = runBlocking {
        var calls = 0
        val accepted = mutableListOf<JsonObject>()
        val coordinator = GcdRecheckCoordinator<Host>(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            timeoutMillis = 20,
            uid = { "af-uid" }, eligible = { true }, validHost = { it.valid },
            fetch = { if (++calls == 1) CompletableDeferred<JsonObject?>().await() else media },
            referrer = { null }, onResult = { _, data -> accepted += data },
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        delay(40)
        coordinator.verifyHome()
        assertEquals(2, calls)
        assertEquals(1, accepted.size)
    }

    private var uid = "af-uid"

    @Test fun `account change preserves consumed process opportunities`() = runBlocking {
        var calls = 0
        val coordinator = coordinator(fetch = { calls++; organic })
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        yield()
        coordinator.verifyHome()
        coordinator.resetForNewIdentity()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        coordinator.verifyHome()
        assertEquals(2, calls)
        assertTrue(coordinator.preHomeComplete.value)
    }

    @Test fun `unknown GCD never reads referrer or publishes a result`() = runBlocking {
        var reads = 0
        val accepted = mutableListOf<JsonObject>()
        val coordinator = coordinator(
            fetch = { Json.parseToJsonElement("""{"af_status":"unknown"}""") as JsonObject },
            referrer = { reads++; media }, onResult = { _, data -> accepted += data },
        )
        coordinator.attach(Host())
        coordinator.sdkOrganic()
        yield()
        assertEquals(0, reads)
        assertTrue(accepted.isEmpty())
    }

    @Test fun `GCD persists Organic before starting referrer`() = runBlocking {
        val persisted = CompletableDeferred<Unit>()
        var reads = 0
        val coordinator = GcdRecheckCoordinator<Host>(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            timeoutMillis = 10_000, uid = { "af-uid" }, eligible = { true }, validHost = { true },
            fetch = { organic }, referrer = { reads++; null }, onResult = { _, _ -> },
            persistResult = { _, _ -> persisted.await() },
        )
        val host = Host()
        coordinator.attach(host)
        coordinator.sdkOrganic()
        yield()
        assertEquals(0, reads)
        persisted.complete(Unit)
        yield()
        assertEquals(1, reads)
    }

    private fun coordinator(
        uid: () -> String = { "af-uid" },
        fetch: suspend (String) -> JsonObject?,
        referrer: suspend (String) -> JsonObject? = { null },
        onResult: (AttributionResultSource, JsonObject) -> Unit = { _, _ -> },
    ) = GcdRecheckCoordinator<Host>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        timeoutMillis = 10_000,
        uid = uid,
        eligible = { true },
        validHost = { it.valid },
        fetch = fetch,
        referrer = referrer,
        onResult = onResult,
    )
}
