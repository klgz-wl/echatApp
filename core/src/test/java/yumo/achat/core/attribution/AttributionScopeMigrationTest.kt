package yumo.achat.core.attribution

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.InstallationPreferences
import yumo.achat.core.auth.LegacyInstallationData
import yumo.achat.core.auth.migrateInstallationPreferences
import yumo.achat.core.backend.AuthSession

class AttributionScopeMigrationTest {
    private val payload = buildJsonObject { put("af_status", "Non-organic"); put("campaign", "real-evidence") }
    private val currentScope = AttributionCompletionScope("user", "device", "test.package", "dev")

    private fun scopedLedger(environment: String, user: String = "user", resultId: String = "old-result"): AttributionLedger {
        val base = AttributionLedger().record(AttributionResultSource.APPSFLYER_SDK, payload, "old-startup", { resultId }).ledger
            .ensureReports(user, { "$resultId-report" }, "device").markSucceeded("$resultId-report", 123)
        val evidence = StoredAttributionCompletion(user, "device", "test.package", environment,
            resultId, "$resultId-report", AttributionResultSource.APPSFLYER_SDK, 123)
        return base.copy(reports = base.reports.map { it.copy(legacyCompletionEvidence = listOf(evidence)) }, nextResultIndex = 100)
    }

    private fun migrate(ledger: AttributionLedger, environment: String = "dev") = migrateInstallationPreferences(
        emptyPreferences(), LegacyInstallationData(ledger = Json.encodeToString(ledger), attribution = payload.toString()),
        "test.package", environment)

    @Test fun `foreign success becomes audit only and fresh same payload needs new durable report`() {
        val old = scopedLedger("prod")
        val migrated = migrate(old)
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertNull(ledger.currentResult())
        assertTrue(ledger.results.isEmpty())
        assertTrue(ledger.reports.isEmpty())
        assertNull(ledger.completionFor(currentScope))
        assertFalse(ledger.hasSucceededMedia("user"))
        assertNull(migrated[InstallationPreferences.ATTRIBUTION])
        val audit = ledger.quarantinedEvidence.single()
        assertEquals(old.results, audit.results)
        assertEquals(old.reports, audit.reports)
        assertEquals(payload, audit.compatibilitySnapshot)
        assertEquals(100L, ledger.nextResultIndex)
        assertEquals(ledger, AttributionLedger.decode(Json.encodeToString(ledger)))
        val fresh = ledger.record(AttributionResultSource.APPSFLYER_SDK, payload, "new-startup", { "new-result" })
            .ledger.ensureReports("user", { "new-report" }, "device")
        assertEquals(100L, fresh.currentResult()!!.index)
        assertEquals("new-result", fresh.currentResult()!!.id)
        assertFalse(fresh.reports.single().succeeded)
        assertEquals("new-report", fresh.pendingReports("user").single().reportId)
        assertEquals(audit, fresh.quarantinedEvidence.single())
    }

    @Test fun `scope reconciliation runs after host migration without importing stale authentication`() {
        val current = emptyPreferences().toMutablePreferences().apply {
            this[InstallationPreferences.MIGRATED] = true
            this[InstallationPreferences.SESSION] = """{"userId":"active","token":"active-token","epoch":"active-epoch"}"""
            this[InstallationPreferences.DEVICE] = "active-device"
            this[InstallationPreferences.LEDGER] = Json.encodeToString(scopedLedger("prod"))
        }
        val migrated = migrateInstallationPreferences(current,
            LegacyInstallationData(session = "broken stale host", hostStatePresent = true), "test.package", "dev")
        assertEquals(current[InstallationPreferences.SESSION], migrated[InstallationPreferences.SESSION])
        assertEquals("active-device", migrated[InstallationPreferences.DEVICE])
        assertTrue(AttributionLedger.decode(migrated[InstallationPreferences.LEDGER]).reports.isEmpty())
        assertEquals(migrated, migrateInstallationPreferences(migrated, LegacyInstallationData(), "test.package", "dev"))
    }

    @Test fun `quarantine preserves maximum historical index and scrubs advertising ids from audit`() {
        val old = scopedLedger("prod")
        val dirty = old.copy(nextResultIndex = 2, results = old.results.map { it.copy(index = 77,
            data = buildJsonObject { put("af_status", "Non-organic"); put("count", 3); put("GPS_ADID", "private-id") }) })
        val ledger = AttributionLedger.decode(migrate(dirty)[InstallationPreferences.LEDGER])
        assertEquals(78L, ledger.nextResultIndex)
        val audit = ledger.quarantinedEvidence.single()
        assertEquals(77L, audit.results.single().index)
        assertEquals(old.reports, audit.reports)
        assertEquals("3", audit.results.single().data["count"].toString())
        assertFalse(Json.encodeToString(ledger).contains("private-id"))
    }

    @Test fun `same scope success for another user survives foreign current user evidence`() {
        val local = scopedLedger("dev", "other", "local")
        val foreign = scopedLedger("prod", "user", "foreign")
        val combined = local.copy(results = local.results + foreign.results, reports = local.reports + foreign.reports,
            latestResultId = "foreign")
        val ledger = AttributionLedger.decode(migrate(combined)[InstallationPreferences.LEDGER])
        assertEquals("local", ledger.currentResult()!!.id)
        assertNotNull(ledger.completionFor(currentScope.copy(userId = "other")))
        assertEquals("local-report", ledger.reports.single().reportId)
        assertEquals("foreign-report", ledger.quarantinedEvidence.single().reports.single().reportId)
        assertEquals("current-user-report", ledger.ensureReports("user", { "current-user-report" }, "device").pendingReports("user").single().reportId)
    }

    @Test fun `mixed shared result retains same scope report and quarantines only foreign report`() {
        val local = scopedLedger("dev", "other")
        val foreign = scopedLedger("prod", "user")
        val combined = local.copy(reports = local.reports + foreign.reports.map { it.copy(reportId = "foreign-report",
            legacyCompletionEvidence = it.legacyCompletionEvidence.map { e -> e.copy(reportId = "foreign-report") }) })
        val ledger = AttributionLedger.decode(migrate(combined)[InstallationPreferences.LEDGER])
        assertEquals("old-result", ledger.currentResult()!!.id)
        assertEquals("other", ledger.reports.single().userId)
        assertNotNull(ledger.completionFor(currentScope.copy(userId = "other")))
        assertNull(ledger.completionFor(currentScope))
        assertEquals("foreign-report", ledger.quarantinedEvidence.single().reports.single().reportId)
        assertTrue(ledger.quarantinedEvidence.single().results.isEmpty())
        val prepared = ledger.ensureReports("user", { "current-report" }, "device")
        assertFalse(prepared.reportFor("user", "old-result")!!.succeeded)
        assertEquals("current-report", prepared.pendingReports("user").single().reportId)
    }

    @Test fun `logical environment switch quarantines even unscoped success and never promotes audit on return`() {
        val unscoped = scopedLedger("prod").copy(reports = scopedLedger("prod").reports.map { it.copy(legacyCompletionEvidence = emptyList()) })
        val prod = migrate(unscoped, "prod")
        assertEquals("old-result", AttributionLedger.decode(prod[InstallationPreferences.LEDGER]).currentResult()!!.id)
        val dev = migrateInstallationPreferences(prod, LegacyInstallationData(), "test.package", "dev")
        val quarantined = AttributionLedger.decode(dev[InstallationPreferences.LEDGER])
        assertNull(quarantined.currentResult())
        assertTrue(quarantined.reports.isEmpty())
        assertEquals(100L, quarantined.nextResultIndex)
        val returned = migrateInstallationPreferences(dev, LegacyInstallationData(), "test.package", "prod")
        assertNull(AttributionLedger.decode(returned[InstallationPreferences.LEDGER]).currentResult())
        assertEquals(quarantined.quarantinedEvidence, AttributionLedger.decode(returned[InstallationPreferences.LEDGER]).quarantinedEvidence)
    }

    @Test fun `recorded current context preserves native other user success without per report legacy scope`() {
        val local = scopedLedger("dev", "other").copy(reports = scopedLedger("dev", "other").reports.map {
            it.copy(legacyCompletionEvidence = emptyList())
        })
        val current = migrate(local).toMutablePreferences()
        val foreign = scopedLedger("prod", "user")
        val combined = local.copy(reports = local.reports + foreign.reports.map { it.copy(reportId = "foreign",
            legacyCompletionEvidence = it.legacyCompletionEvidence.map { e -> e.copy(reportId = "foreign") }) })
        current[InstallationPreferences.LEDGER] = Json.encodeToString(combined)
        val migrated = migrateInstallationPreferences(current, LegacyInstallationData(), "test.package", "dev")
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertEquals("other", ledger.reports.single().userId)
        assertNotNull(ledger.completionFor(currentScope.copy(userId = "other")))
        assertEquals("foreign", ledger.quarantinedEvidence.single().reports.single().reportId)
    }

    @Test fun `real dispatcher cannot publish foreign success and recovers through fresh observation and POST`() = runBlocking {
        val migrated = migrate(scopedLedger("prod"))
        val installation = InstallationPreferences(MemoryPreferences())
        installation.edit { prefs ->
            prefs[InstallationPreferences.LEDGER] = checkNotNull(migrated[InstallationPreferences.LEDGER])
            prefs[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            prefs[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(installation)
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        var ids = 0
        try {
            val dispatcher = LedgerAttributionReportDispatcher(1, workerScope, store, "new-startup", 0, {},
                report = { _, pending, _, _ -> sent += pending.reportId; release.await() },
                completionScope = { _, _ -> currentScope }, deviceId = { "device" }, newId = { "new-${++ids}" })
            dispatcher.updateSession(AuthSession("user", "t", "r", "s", true, "epoch"))
            yield()
            assertNull(dispatcher.receipts.value)
            assertTrue(sent.isEmpty())
            assertFalse(dispatcher.storageFailed())
            assertNull(dispatcher.currentResult())
            dispatcher.updateResult(AttributionResultSource.APPSFLYER_SDK, payload)
            yield()
            assertEquals(1, sent.size)
            assertNotEquals("old-result-report", sent.single())
            assertNull(dispatcher.receipts.value)
            assertFalse(store.snapshot().reports.single().succeeded)
            release.complete(Unit)
            yield()
            assertEquals(sent.single(), dispatcher.receipts.value?.reportId)
            assertTrue(store.snapshot().reports.single().succeeded)
            assertTrue(store.snapshot().quarantinedEvidence.single().reports.single().succeeded)
        } finally { workerScope.cancel() }
    }

    @Test fun `real dispatcher cannot publish a foreign organic success either`() = runBlocking {
        val old = scopedLedger("prod")
        val organic = old.copy(results = old.results.map { it.copy(data = buildJsonObject { put("af_status", "Organic") }) })
        val installation = InstallationPreferences(MemoryPreferences())
        installation.edit { it[InstallationPreferences.LEDGER] = checkNotNull(migrate(organic)[InstallationPreferences.LEDGER]) }
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dispatcher = LedgerAttributionReportDispatcher(1, workerScope, DataStoreAttributionLedgerStore(installation), "startup", 0, {},
                report = { _, _, _, _ -> error("Foreign evidence must not be delivered") },
                completionScope = { _, _ -> currentScope }, deviceId = { "device" })
            dispatcher.updateSession(AuthSession("user", "t", "r", "s", true, "epoch"))
            yield()
            assertNull(dispatcher.receipts.value)
            assertNull(dispatcher.currentResult())
            assertFalse(dispatcher.storageFailed())
        } finally { workerScope.cancel() }
    }
}
