package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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
