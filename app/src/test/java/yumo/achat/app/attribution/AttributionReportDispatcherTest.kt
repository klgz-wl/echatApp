package yumo.achat.app.attribution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.AttributionStage
import yumo.achat.core.backend.AuthSession

class AttributionReportDispatcherTest {
    @Test fun `failed old result is deferred while newer result is delivered`() = runBlocking {
        val store = MemoryLedgerStore()
        val organic = buildJsonObject { put("af_status", "Organic") }
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val sent = mutableListOf<String>()
        var ids = 0
        val dispatcher = dispatcher(store, attempts = 1, newId = { "id-${++ids}" }) { _, result, _ ->
            sent += result.data["af_status"].toString()
            if (result.data == organic) error("offline")
        }
        dispatcher.updateResult(AttributionResultSource.APPSFLYER_SDK, organic)
        dispatcher.updateResult(AttributionResultSource.GCD_HTTP, media)
        dispatcher.updateSession(session("user"))
        yield()

        assertEquals(listOf("\"Organic\"", "\"Non-organic\""), sent)
        assertEquals(media, dispatcher.currentResult()?.data)
        assertNotNull(dispatcher.receipts.value)
    }

    @Test fun `concurrent kicks do not multiply one failed retry batch`() = runBlocking {
        val store = MemoryLedgerStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val dispatcher = dispatcher(store, attempts = 2) { _, _, _ ->
            attempts++
            entered.complete(Unit)
            release.await()
            error("offline")
        }
        dispatcher.updateResult(AttributionResultSource.APPSFLYER_SDK, buildJsonObject { put("af_status", "Organic") })
        dispatcher.updateSession(session("user"))
        entered.await()
        dispatcher.updateSession(session("user"))
        release.complete(Unit)
        yield()

        assertEquals(2, attempts)
    }

    @Test fun `report metadata keeps stable id and marks recovered stage`() = runBlocking {
        val store = MemoryLedgerStore()
        val metadata = mutableListOf<yumo.achat.core.attribution.AttributionReportMetadata>()
        val dispatcher = LedgerAttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = store,
            startupId = "current-startup",
            startupNanos = 10,
            retryDelay = {},
            report = { _, _, value -> metadata += value },
            nowNanos = { 1_000_010 },
            newId = { "stable-id" },
        )
        store.record(
            AttributionResultSource.GCD_HTTP,
            buildJsonObject { put("af_status", "Organic") },
            startupId = "old-startup",
            id = { "result" },
        )
        dispatcher.updateSession(session("user"))
        yield()

        assertEquals("stable-id", metadata.single().reportId)
        assertEquals(AttributionStage.STARTUP_RECOVERY, metadata.single().stage)
        assertEquals(AttributionResultSource.GCD_HTTP, metadata.single().source)
    }

    @Test fun `explicit retry resumes a deferred report with the same report id`() = runBlocking {
        val store = MemoryLedgerStore()
        var online = false
        val reportIds = mutableListOf<String>()
        val dispatcher = dispatcher(store, attempts = 1, newId = { "stable" }) { _, _, metadata ->
            reportIds += metadata.reportId
            if (!online) error("offline")
        }
        dispatcher.updateResult(AttributionResultSource.APPSFLYER_SDK, buildJsonObject { put("af_status", "Organic") })
        dispatcher.updateSession(session("user"))
        yield()
        online = true
        dispatcher.retryPending()
        yield()

        assertEquals(listOf("stable", "stable"), reportIds)
        assertNotNull(dispatcher.receipts.value)
    }

    @Test fun `outbox persistence failure becomes observable storage failure`() {
        val failing = object : AttributionLedgerStore {
            override fun snapshot(): AttributionLedger = error("corrupt")
            override fun record(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String) = error("corrupt")
            override fun ensureReports(userId: String, reportId: () -> String): AttributionLedger = error("corrupt")
            override fun markSucceeded(reportId: String, atMillis: Long) = error("corrupt")
        }
        val dispatcher = dispatcher(failing, attempts = 1) { _, _, _ -> }
        dispatcher.updateSession(session("user"))
        assertEquals(true, dispatcher.storageFailed())
    }

    private fun dispatcher(
        store: AttributionLedgerStore,
        attempts: Int,
        newId: () -> String = { "id" },
        report: suspend (AuthSession, StoredAttributionResult, yumo.achat.core.attribution.AttributionReportMetadata) -> Unit,
    ) = LedgerAttributionReportDispatcher(
        attempts = attempts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        store = store,
        startupId = "startup",
        startupNanos = 0,
        retryDelay = {},
        report = report,
        newId = newId,
    )

    private class MemoryLedgerStore : AttributionLedgerStore {
        private var ledger = AttributionLedger.empty()
        override fun snapshot() = ledger
        override fun record(
            source: AttributionResultSource,
            data: JsonObject,
            startupId: String,
            id: () -> String,
        ): StoredAttributionResult = ledger.record(source, data, startupId, id).also { ledger = it.ledger }.result
        override fun ensureReports(userId: String, reportId: () -> String): AttributionLedger =
            ledger.ensureReports(userId, reportId).also { ledger = it }
        override fun markSucceeded(reportId: String, atMillis: Long) {
            ledger = ledger.markSucceeded(reportId, atMillis)
        }
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
