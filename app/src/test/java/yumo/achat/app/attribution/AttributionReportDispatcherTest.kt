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
    @Test fun `legacy pending report without known device is withheld after migration`() = runBlocking {
        val raw = """{"latestResultId":"result","results":[{"id":"result","source":"APPSFLYER_SDK","data":{"af_status":"Organic"},"index":1,"startupId":"old"}],"reports":[{"reportId":"stable-report","resultId":"result","userId":"old-user"}]}"""
        val store = MemoryLedgerStore(AttributionLedger.decode(raw))
        var sends = 0
        val dispatcher = dispatcher(store, attempts = 1, deviceId = { "androidid12345678" }) { _, _, _, _ -> sends++ }
        dispatcher.updateSession(session("old-user"))
        yield()

        assertEquals(0, sends)
        assertEquals(true, dispatcher.isExhausted("old-user", "result"))
        assertEquals("stable-report", store.snapshot().reports.single().reportId)
    }

    @Test fun `deferred report keeps original device after provider changes`() = runBlocking {
        val store = MemoryLedgerStore()
        var currentDevice = "old-uuid"
        var online = false
        val sent = mutableListOf<Pair<String, String>>()
        val dispatcher = dispatcher(store, attempts = 1, deviceId = { currentDevice }) { _, pending, _, metadata ->
            sent += pending.deviceId to metadata.reportId
            if (!online) error("offline")
        }
        dispatcher.updateResult(AttributionResultSource.APPSFLYER_SDK, buildJsonObject { put("af_status", "Organic") })
        dispatcher.updateSession(session("old-user"))
        yield()
        currentDevice = "new-android-id"
        online = true
        dispatcher.retryPending()
        yield()

        assertEquals(listOf("old-uuid" to "id", "old-uuid" to "id"), sent)
        assertEquals("old-uuid", store.snapshot().reports.single().deviceId)
    }

    @Test fun `sign out suppresses receipt from in flight old report`() = runBlocking {
        val store = MemoryLedgerStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = dispatcher(store, attempts = 1) { _, _, _, _ ->
            entered.complete(Unit)
            release.await()
        }
        dispatcher.updateResult(AttributionResultSource.GCD_HTTP, buildJsonObject { put("af_status", "Non-organic") })
        dispatcher.updateSession(session("old-user"))
        entered.await()
        dispatcher.clearSession()
        release.complete(Unit)
        yield()

        assertEquals(null, dispatcher.receipts.value)
    }
    @Test fun `failed old result is deferred while newer result is delivered`() = runBlocking {
        val store = MemoryLedgerStore()
        val organic = buildJsonObject { put("af_status", "Organic") }
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val sent = mutableListOf<String>()
        var ids = 0
        val dispatcher = dispatcher(store, attempts = 1, newId = { "id-${++ids}" }) { _, _, result, _ ->
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
        val dispatcher = dispatcher(store, attempts = 2) { _, _, _, _ ->
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
            report = { _, _, _, value -> metadata += value },
            completionScope = { session, pending -> completionScope(session, pending.deviceId) },
            deviceId = { "install" },
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
        val dispatcher = dispatcher(store, attempts = 1, newId = { "stable" }) { _, _, _, metadata ->
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
            override fun ensureReports(userId: String, reportId: () -> String, deviceId: String): AttributionLedger = error("corrupt")
            override fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope) = error("corrupt")
            override fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion? = error("corrupt")
        }
        val dispatcher = dispatcher(failing, attempts = 1) { _, _, _, _ -> }
        dispatcher.updateSession(session("user"))
        assertEquals(true, dispatcher.storageFailed())
    }

    @Test fun `remote media success is not completed when final persistence fails`() = runBlocking {
        val delegate = MemoryLedgerStore()
        val failing = object : AttributionLedgerStore by delegate {
            override fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope) {
                error("disk full")
            }
        }
        var completionCallbacks = 0
        val dispatcher = LedgerAttributionReportDispatcher(
            attempts = 1,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = failing,
            startupId = "startup",
            startupNanos = 0,
            retryDelay = {},
            report = { _, _, _, _ -> Unit },
            completionScope = { session, pending -> completionScope(session, pending.deviceId) },
            deviceId = { "install" },
            onCompletionPersisted = { _, _ -> completionCallbacks++ },
            newId = { "stable" },
        )
        dispatcher.updateResult(
            AttributionResultSource.APPSFLYER_SDK,
            buildJsonObject { put("af_status", "Non-organic") },
        )
        dispatcher.updateSession(session("user"))
        yield()

        assertEquals(0, completionCallbacks)
        assertEquals(null, dispatcher.receipts.value)
        assertEquals(false, delegate.snapshot().reports.single().succeeded)
    }

    private fun dispatcher(
        store: AttributionLedgerStore,
        attempts: Int,
        newId: () -> String = { "id" },
        deviceId: () -> String = { "install" },
        report: suspend (AuthSession, StoredAttributionReport, StoredAttributionResult, yumo.achat.core.attribution.AttributionReportMetadata) -> Unit,
    ) = LedgerAttributionReportDispatcher(
        attempts = attempts,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        store = store,
        startupId = "startup",
        startupNanos = 0,
        retryDelay = {},
        report = report,
        completionScope = { session, pending -> completionScope(session, pending.deviceId) },
        deviceId = deviceId,
        newId = newId,
    )

    private class MemoryLedgerStore(initial: AttributionLedger = AttributionLedger.empty()) : AttributionLedgerStore {
        private var ledger = initial
        override fun snapshot() = ledger
        override fun record(
            source: AttributionResultSource,
            data: JsonObject,
            startupId: String,
            id: () -> String,
        ): StoredAttributionResult = ledger.record(source, data, startupId, id).also { ledger = it.ledger }.result
        override fun ensureReports(userId: String, reportId: () -> String, deviceId: String): AttributionLedger =
            ledger.ensureReports(userId, reportId, deviceId).also { ledger = it }
        override fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope) {
            ledger = ledger.markReportSucceeded(reportId, atMillis, scope)
        }
        override fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion? =
            ledger.completionFor(scope)
    }

    private fun session(userId: String) = AuthSession(
        userId = userId,
        token = "token",
        refreshToken = "refresh",
        sessionId = "session",
        isAnonymous = true,
        epoch = "epoch-one",
    )

    private fun completionScope(session: AuthSession, deviceId: String) = AttributionCompletionScope(
        userId = session.userId,
        installDeviceId = deviceId,
        packageName = "yumo.achat.app",
        environment = "dev",
    )
}
