package yumo.achat.core.attribution

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.InstallationPreferences

class AttributionLedgerStorageTest {
    private val media = Json.parseToJsonElement("""{"af_status":"Non-organic","campaign":"one"}""") as kotlinx.serialization.json.JsonObject
    private val scope = AttributionCompletionScope("user", "device", "package", "dev")

    private fun ambiguousLedger(): AttributionLedger {
        val ledger = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "p", { "ambiguous" }).ledger
            .ensureReports("user", { "report-A" }, "device")
        return ledger.copy(reports = ledger.reports + ledger.reports.single().copy(reportId = "report-B", succeeded = true))
    }

    @Test fun `current canonical report conflict makes ensure reports fail without changing facts`() = runBlocking {
        val installation = InstallationPreferences(MemoryPreferences())
        val ambiguous = ambiguousLedger()
        installation.edit { it[InstallationPreferences.LEDGER] = Json.encodeToString(ambiguous) }
        val store = DataStoreAttributionLedgerStore(installation)
        assertThrows(IllegalStateException::class.java) {
            ambiguous.ensureReports("user", { error("must not create a third identity") }, "device")
        }
        assertThrows(IllegalStateException::class.java) {
            store.ensureReports("user", { error("must not create a third identity") }, "device")
        }
        assertEquals(ambiguous, store.snapshot())
        assertNull(store.snapshot().reportFor("user", "ambiguous"))
        assertTrue(store.snapshot().pendingReports("user").isEmpty())
    }

    @Test fun `older ambiguous pair stays quarantined while newer current target can proceed`() = runBlocking {
        val installation = InstallationPreferences(MemoryPreferences())
        val older = ambiguousLedger()
        val newer = older.record(AttributionResultSource.APPSFLYER_SDK, media, "p", { "new" }).ledger
        installation.edit { it[InstallationPreferences.LEDGER] = Json.encodeToString(newer) }
        val store = DataStoreAttributionLedgerStore(installation)
        val saved = store.ensureReports("user", { "new-report" }, "device")
        assertEquals(listOf("new-report"), saved.pendingReports("user").map { it.reportId })
        assertEquals(older.reports, saved.reports.filter { it.resultId == "ambiguous" })
        assertEquals("new-report", saved.reportFor("user", "new")?.reportId)
        assertEquals("new-report", newer.ensureReports("user", { "new-report" }, "device").reportFor("user", "new")?.reportId)
    }

    @Test fun `another users current canonical conflict does not block own report preparation`() {
        val ledger = ambiguousLedger().ensureReports("other", { "other-report" }, "other-device")
        assertEquals("other-report", ledger.reportFor("other", "ambiguous")?.reportId)
        assertEquals(listOf("other-report"), ledger.pendingReports("other").map { it.reportId })
    }

    @Test fun `restore barrier suppresses next record inside the serialized storage transform`() = runBlocking {
        val data = MemoryPreferences()
        val installation = InstallationPreferences(data)
        val ledger = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "old", { "old-result" }).ledger
            .ensureReports("user", { "old-report" }, "device").markSucceeded("old-report", 1)
        installation.edit {
            it[InstallationPreferences.LEDGER] = Json.encodeToString(ledger)
            it[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            it[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(installation)
        var allowed = true
        var inside = false
        data.insideTransform = { inside = it }
        val restored = store.restoreCompletion(scope) {
            assertTrue(inside)
            allowed = false
        }
        assertEquals("old-report", restored?.reportId)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            store.recordGuarded(AttributionResultSource.APPSFLYER_SDK, media, "new", { "new-result" }, { allowed })
        }
        assertEquals(ledger, store.snapshot())
    }

    @Test fun `failed initial commit retains result and report candidates across retry`() = runBlocking {
        val data = MemoryPreferences()
        val installation = InstallationPreferences(data)
        installation.edit {
            it[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            it[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(installation)
        var generated = 0
        data.failWrites = true
        assertThrows(IllegalStateException::class.java) {
            store.record(AttributionResultSource.GCD_HTTP, media, "process") { "result-${++generated}" }
        }
        val failed = AttributionLedger.decode(data.lastAttempt!![InstallationPreferences.LEDGER])
        data.failWrites = false
        val result = store.record(AttributionResultSource.GCD_HTTP, media, "process") { "result-${++generated}" }
        assertEquals(failed.results.single().id, result.id)
        assertEquals(failed.reports.single().reportId, store.snapshot().reports.single().reportId)
        assertEquals(1, generated)
    }

    @Test fun `sanitized candidates deduplicate by source and reuse canonical disk ids after failure`() = runBlocking {
        val data = MemoryPreferences()
        val installation = InstallationPreferences(data)
        installation.edit {
            it[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            it[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(installation)
        val dirty = Json.parseToJsonElement("""{"gaid":"secret","campaign":"one","af_status":"Non-organic"}""") as kotlinx.serialization.json.JsonObject
        data.failWrites = true
        assertThrows(IllegalStateException::class.java) { store.record(AttributionResultSource.GCD_HTTP, dirty, "p") { "candidate" } }
        data.failWrites = false
        val canonical = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "disk", { "canonical" }).ledger
            .ensureReports("user", { "canonical-report" }, "device")
        installation.edit { it[InstallationPreferences.LEDGER] = Json.encodeToString(canonical) }
        assertEquals("canonical", store.record(AttributionResultSource.GCD_HTTP, dirty, "retry") { error("reuse disk") }.id)
        assertEquals("canonical-report", store.snapshot().reports.single().reportId)
        assertEquals("sdk", store.record(AttributionResultSource.APPSFLYER_SDK, media, "retry") { "sdk" }.id)
        assertEquals(2, store.snapshot().reports.size)
    }

    @Test fun `record committed before restore barrier is included in restored target selection`() = runBlocking {
        val data = MemoryPreferences()
        val installation = InstallationPreferences(data)
        val old = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "p", { "old" }).ledger
            .ensureReports("user", { "old-report" }, "device").markSucceeded("old-report", 1)
        installation.edit {
            it[InstallationPreferences.LEDGER] = Json.encodeToString(old)
            it[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            it[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(installation)
        store.record(AttributionResultSource.APPSFLYER_SDK, media, "p") { "new" }
        assertNull(store.restoreCompletion(scope) { error("new pending target cannot restore old success") })
        assertEquals("new", store.snapshot().latestResultId)
    }

    @Test fun `guard checked inside delayed datastore transform prevents stale commit`() {
        val data = MemoryPreferences()
        val store = DataStoreAttributionLedgerStore(InstallationPreferences(data))
        var allowed = true
        data.beforeTransform = { allowed = false }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            store.recordGuarded(AttributionResultSource.GCD_HTTP, media, "process", { "result" }, { allowed })
        }
        assertTrue(store.snapshot().results.isEmpty())
    }

    @Test fun `failed ensure reports reuses prepared report identity`() {
        val data = MemoryPreferences()
        val store = DataStoreAttributionLedgerStore(InstallationPreferences(data))
        store.record(AttributionResultSource.GCD_HTTP, media, "process") { "result" }
        var generated = 0
        data.failWrites = true
        assertThrows(IllegalStateException::class.java) { store.ensureReports("user", { "report-${++generated}" }, "device") }
        data.failWrites = false
        val saved = store.ensureReports("user", { "report-${++generated}" }, "device")
        assertEquals("report-1", saved.reports.single().reportId)
        assertEquals(1, generated)
    }

    @Test fun `ledger models redact diagnostic strings without changing stored JSON`() {
        val ledger = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "private-startup", { "private-result" }).ledger
            .ensureReports("private-user", { "private-report" }, "private-device").markSucceeded("private-report", 1)
        val completion = ledger.completionFor(scope.copy(userId = "private-user", installDeviceId = "private-device"))!!
        val values = listOf(ledger, ledger.results.single(), ledger.reports.single(), completion,
            scope.copy(userId = "private-user", installDeviceId = "private-device"))
        for (value in values) {
            assertFalse(value.toString().contains("private-"))
            assertFalse(value.toString().contains("campaign"))
            assertFalse(value.toString().contains("Non-organic"))
        }
        val encoded = Json.encodeToString(ledger)
        assertTrue(encoded.contains("private-report"))
        assertTrue(encoded.contains("private-device"))
        assertEquals(ledger, AttributionLedger.decode(encoded))
    }

    @Test fun `newer media invalidates restored completion without removing old success`() {
        val old = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "process", { "old" }).ledger
            .ensureReports("user", { "report" }, "device").markReportSucceeded("report", 123, scope)
        val newer = old.record(AttributionResultSource.GCD_HTTP,
            Json.parseToJsonElement("""{"af_status":"Non-organic","campaign":"two"}""") as kotlinx.serialization.json.JsonObject,
            "process", { "new" }).ledger
        assertNull(newer.completionFor(scope))
        assertTrue(newer.reports.single().succeeded)
        assertFalse(Json.encodeToString(old).contains("completions"))
    }

    @Test fun `privacy cleanup reuses identity and preserves historical success`() {
        val raw = """{"results":[{"id":"r","source":"GCD_HTTP","data":{"af_status":"Non-organic","campaign":"one","gaid":"secret","nested":[{"GPS_ADID":"secret","count":2}]},"index":1,"startupId":"old"}],"latestResultId":"r","nextResultIndex":2,"reports":[{"reportId":"p","resultId":"r","userId":"user","succeeded":true}]}"""
        val ledger = AttributionLedger.decode(raw)
        val duplicate = ledger.record(AttributionResultSource.GCD_HTTP, ledger.results.single().data, "new", { error("duplicate") })
        assertFalse(duplicate.created)
        assertFalse(Json.encodeToString(duplicate.ledger).contains("secret"))
        assertEquals("p", duplicate.ledger.completionFor(scope)?.reportId)
        assertEquals(0L, duplicate.ledger.completionFor(scope)?.completedAtMillis)
    }

    @Test fun `record commits ledger reports and raw compatibility snapshot together`() = runBlocking {
        val data = MemoryPreferences()
        val storage = InstallationPreferences(data)
        storage.edit { it[InstallationPreferences.SESSION] = """{"userId":"user","token":"t","refreshToken":"r","epoch":"e"}"""
            it[InstallationPreferences.DEVICE] = "device" }
        val store = DataStoreAttributionLedgerStore(storage)
        store.record(AttributionResultSource.GCD_HTTP, media, "process", { "result" })
        val saved = storage.read()
        val ledger = AttributionLedger.decode(saved[InstallationPreferences.LEDGER])
        assertEquals("user", ledger.reports.single().userId)
        assertEquals("device", ledger.reports.single().deviceId)
        assertEquals(media.toString(), saved[InstallationPreferences.ATTRIBUTION])
        assertEquals("result", ledger.latestResultId)
    }

    @Test fun `failed write never publishes reliable result`() {
        val data = MemoryPreferences(failWrites = true)
        val store = DataStoreAttributionLedgerStore(InstallationPreferences(data))
        assertThrows(IllegalStateException::class.java) {
            store.record(AttributionResultSource.GCD_HTTP, media, "process", { "result" })
        }
        assertTrue(store.snapshot().results.isEmpty())
    }

    @Test fun `frozen device must match completion request`() {
        val ledger = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "p", { "r" }).ledger
            .ensureReports("user", { "report" }, "old-device").markSucceeded("report", 1)
        assertNull(ledger.completionFor(scope))
        assertEquals("report", ledger.completionFor(scope.copy(installDeviceId = "old-device"))?.reportId)
    }

    @Test fun `store validates actual scope before reusing completion`() = runBlocking {
        val preferences = InstallationPreferences(MemoryPreferences())
        val ledger = AttributionLedger().record(AttributionResultSource.GCD_HTTP, media, "p", { "r" }).ledger
            .ensureReports("user", { "report" }, "device").markSucceeded("report", 1)
        preferences.edit {
            it[InstallationPreferences.LEDGER] = Json.encodeToString(ledger)
            it[InstallationPreferences.SESSION] = """{"userId":"user"}"""
            it[InstallationPreferences.DEVICE] = "device"
        }
        val store = DataStoreAttributionLedgerStore(preferences) { it.packageName == "package" && it.environment == "dev" }
        assertNotNull(store.completion(scope))
        assertNull(store.completion(scope.copy(packageName = "other")))
        assertNull(store.completion(scope.copy(environment = "prod")))
        assertNull(store.completion(scope.copy(userId = "other")))
    }

    @Test fun `legacy explicit completion preserves device and rejects conflicting scope`() {
        val raw = """{"latestResultId":"r","results":[{"id":"r","source":"GCD_HTTP","data":{"af_status":"Non-organic"},"index":1,"startupId":"old"}],"reports":[{"reportId":"p","resultId":"r","userId":"user","succeeded":true}],"completions":[{"userId":"user","installDeviceId":"old-device","packageName":"package","environment":"dev","resultId":"r","reportId":"p","source":"GCD_HTTP","completedAtMillis":1}]}"""
        val ledger = AttributionLedger.decode(raw)
        assertNull(ledger.completionFor(scope.copy(installDeviceId = "old-device", environment = "prod")))
        assertNull(ledger.completionFor(scope))
        assertEquals("p", ledger.completionFor(scope.copy(installDeviceId = "old-device"))?.reportId)
        assertEquals("old-device", ledger.reports.single().deviceId)
        assertFalse(Json.encodeToString(ledger).contains("completions"))
    }
}

internal class MemoryPreferences(var failWrites: Boolean = false) : DataStore<Preferences> {
    var lastAttempt: Preferences? = null
    var beforeTransform: () -> Unit = {}
    var insideTransform: (Boolean) -> Unit = {}
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        beforeTransform()
        insideTransform(true)
        val updated = try { transform(state.value) } finally { insideTransform(false) }
        lastAttempt = updated
        check(!failWrites) { "disk unavailable" }
        state.value = updated
        return updated
    }
}
