package yumo.achat.core.auth

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.attribution.AttributionLedger
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.AttributionCompletionScope
import kotlinx.serialization.json.jsonObject
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class InstallationPreferencesTest {
    @Test fun `owner observer contains read failure clears stale owner and stops without uncaught diagnostics`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error })
        val release = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            var collections = 0
            override val data = flow<Preferences> {
                collections++
                release.await()
                throw IllegalStateException("private raw JSON must never escape observer")
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        }
        try {
            val publication = OwnerPublication(store, observerScope)
            val valid = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.DEVICE] = "device" }
            publication.publish(valid)
            assertNotNull(publication.owner.get())
            release.complete(Unit)
            publication.observation.join()
            assertTrue(failures.isEmpty())
            assertNull(publication.owner.get())
            assertEquals(1, store.collections)
            assertFalse(publication.observation.isActive)
            publication.publish(valid)
            assertEquals("device", publication.owner.get()?.deviceId)
        } finally { observerScope.cancel() }
    }

    @Test fun `owner observer contains malformed session publication failure`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error })
        val invalid = emptyPreferences().toMutablePreferences().apply {
            this[InstallationPreferences.DEVICE] = "device"
            this[InstallationPreferences.SESSION] = "{private invalid JSON"
        }
        val store = object : DataStore<Preferences> {
            override val data = flowOf<Preferences>(invalid)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        }
        try {
            val publication = OwnerPublication(store, observerScope)
            publication.observation.join()
            assertTrue(failures.isEmpty())
            assertNull(publication.owner.get())
        } finally { observerScope.cancel() }
    }

    @Test fun `owner observer propagates cancellation`() = runBlocking {
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw CancellationException("cancel observer") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        }
        try {
            val publication = OwnerPublication(store, observerScope)
            publication.observation.join()
            assertTrue(publication.observation.isCancelled)
        } finally { observerScope.cancel() }
    }

    @Test fun `legacy cleanup retries failed commit without touching other metadata`() {
        val values = mutableMapOf<String, Any>(
            InstallationPreferences.LEDGER.name to """{"latestResultId":"r","results":[{"id":"r","source":"gcd_http","data":{"af_status":"Non-organic","nested":[{"gaid":"secret","flag":true}]},"index":1,"startupId":"old"}],"reports":[{"reportId":"p","resultId":"r","userId":"u","deviceId":"frozen","succeeded":true}]}""",
            InstallationPreferences.ATTRIBUTION.name to """{"nested":{"GPS_ADID":"secret","count":2}}""",
            "payment_epoch" to "untouched", "unrelated" to 42,
        )
        assertTrue(legacyAttributionNeedsCleanup(values))
        assertThrows(IllegalStateException::class.java) { cleanLegacyAttribution(values) { false } }
        assertTrue(legacyAttributionNeedsCleanup(values))
        cleanLegacyAttribution(values) { cleaned ->
            assertEquals(setOf(InstallationPreferences.LEDGER.name, InstallationPreferences.ATTRIBUTION.name), cleaned.keys)
            values.putAll(cleaned)
            true
        }
        assertFalse(legacyAttributionNeedsCleanup(values))
        assertFalse(values.values.any { it.toString().contains("secret") })
        val report = AttributionLedger.decode(values[InstallationPreferences.LEDGER.name] as String).reports.single()
        assertEquals("p", report.reportId)
        assertEquals("frozen", report.deviceId)
        assertTrue(report.succeeded)
        assertEquals("untouched", values["payment_epoch"])
        assertEquals(42, values["unrelated"])
    }

    @org.junit.Test fun `known foreign legacy completion scope is not erased into current success`() {
        val raw = """{"latestResultId":"r","results":[{"id":"r","source":"APPSFLYER_SDK","data":{"af_status":"Non-organic"},"index":1,"startupId":"old"}],"reports":[{"reportId":"p","resultId":"r","userId":"u","succeeded":true}],"completions":[{"userId":"u","installDeviceId":"device","packageName":"other.package","environment":"prod","resultId":"r","reportId":"p","source":"APPSFLYER_SDK","completedAtMillis":1}]}"""
        val migrated = migrateInstallationPreferences(emptyPreferences(),
            LegacyInstallationData(ledger = raw, deviceId = "device"), packageName = "test.package", environment = "dev")
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertTrue(ledger.reports.isEmpty())
        assertTrue(ledger.quarantinedEvidence.single().reports.single().succeeded)
        assertEquals("p", ledger.quarantinedEvidence.single().reports.single().reportId)
        assertEquals("r", ledger.quarantinedEvidence.single().results.single().id)
        assertNull(ledger.completionFor(AttributionCompletionScope("u", "device", "test.package", "dev")))
        assertNull(ledger.completionFor(AttributionCompletionScope("u", "device", "other.package", "prod")))
        assertEquals(ledger, AttributionLedger.decode(Json.encodeToString(ledger)))
    }

    @Test fun `foreign evidence for another user does not disable eligible current success`() {
        val foreign = """{"latestResultId":"r","results":[{"id":"r","source":"gcd_http","data":{"af_status":"Non-organic"},"index":1,"startupId":"old"}],"reports":[{"reportId":"foreign","resultId":"r","userId":"other","succeeded":true},{"reportId":"local","resultId":"r","userId":"current","deviceId":"device","succeeded":true}],"completions":[{"userId":"other","installDeviceId":"device","packageName":"other.package","environment":"prod","resultId":"r","reportId":"foreign","source":"gcd_http","completedAtMillis":1}]}"""
        val decoded = AttributionLedger.decode(foreign)
        val currentEvidence = yumo.achat.core.attribution.StoredAttributionCompletion("current", "device", "test.package", "dev",
            "r", "local", AttributionResultSource.GCD_HTTP, 1)
        val sameScope = decoded.copy(reports = decoded.reports.map {
            if (it.reportId == "local") it.copy(legacyCompletionEvidence = listOf(currentEvidence)) else it
        })
        val migrated = migrateInstallationPreferences(emptyPreferences(), LegacyInstallationData(ledger = Json.encodeToString(sameScope)),
            "test.package", "dev")
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertEquals("local", ledger.completionFor(AttributionCompletionScope("current", "device", "test.package", "dev"))?.reportId)
        assertNull(ledger.completionFor(AttributionCompletionScope("other", "device", "test.package", "dev")))
        assertEquals(1, ledger.quarantinedEvidence.single().reports.single().legacyCompletionEvidence.size)
    }

    @Test fun `orphaned legacy completion remains audit evidence and never fabricates success`() {
        val raw = """{"completions":[{"userId":"u","installDeviceId":"device","packageName":"package","environment":"dev","resultId":"missing","reportId":"missing-report","source":"gcd_http","completedAtMillis":1}]}"""
        val migrated = migrateInstallationPreferences(emptyPreferences(), LegacyInstallationData(ledger = raw), "package", "dev")
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertEquals(1, ledger.quarantinedLegacyCompletionEvidence.size)
        assertTrue(ledger.results.isEmpty())
        assertTrue(ledger.reports.isEmpty())
        assertNull(ledger.completionFor(AttributionCompletionScope("u", "device", "package", "dev")))
    }

    private val uuid = "12345678-1234-4234-8234-123456789abc"
    private val staleSession = """{"userId":"A","token":"old","refreshToken":"r","epoch":"A"}"""

    @Test fun `authoritative handwritten host replaces stale destination session`() {
        val current = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.SESSION] = staleSession }
        val active = """{"userId":"B","token":"active","refreshToken":"r","epoch":"B"}"""
        val migrated = migrateInstallationPreferences(current, LegacyInstallationData(session = active, hostStatePresent = true))
        assertEquals("B", Json.parseToJsonElement(migrated[InstallationPreferences.SESSION]!!).jsonObject["userId"]!!.toString().trim('"'))
    }

    @Test fun `valid host session and device migrate together while earlier report devices stay frozen`() {
        val core = media("core-result").ensureReports("A", { "core-report" }, "original-report-device")
            .ensureReports("earlier-user", { "missing-device-report" })
        val host = media("host-result").copy(nextResultIndex = 10,
            results = media("host-result").results.map { it.copy(index = 9) })
            .ensureReports("B", { "host-report" }, "host-device").markSucceeded("host-report", 123)
        val current = emptyPreferences().toMutablePreferences().apply {
            this[InstallationPreferences.SESSION] = staleSession
            this[InstallationPreferences.DEVICE] = "core-device"
            this[InstallationPreferences.LEDGER] = Json.encodeToString(core)
        }
        val legacy = LegacyInstallationData(deviceId = "host-device", hostStatePresent = true,
            session = """{"userId":"B","token":"active","refreshToken":"r","epoch":"B"}""",
            ledger = Json.encodeToString(host))
        val migrated = migrateInstallationPreferences(current, legacy, "test.package", "dev")
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertEquals("B", Json.parseToJsonElement(migrated[InstallationPreferences.SESSION]!!).jsonObject["userId"]!!.toString().trim('"'))
        assertEquals("host-device", migrated[InstallationPreferences.DEVICE])
        assertEquals("original-report-device", ledger.reportFor("A", "core-result")!!.deviceId)
        assertEquals("core-device", ledger.reportFor("earlier-user", "core-result")!!.deviceId)
        assertEquals("host-device", ledger.reportFor("B", "host-result")!!.deviceId)
        assertEquals("host-report", ledger.completionFor(AttributionCompletionScope("B", "host-device", "test.package", "dev"))?.reportId)
        assertEquals("core-device", current[InstallationPreferences.DEVICE])
    }

    @Test fun `explicit host logout clears stale session and payment owner`() {
        val current = emptyPreferences().toMutablePreferences().apply {
            this[InstallationPreferences.SESSION] = staleSession
            this[InstallationPreferences.PAYMENT_USER] = "A"
            this[InstallationPreferences.PAYMENT_EPOCH] = "old-payment"
        }
        val migrated = migrateInstallationPreferences(current, LegacyInstallationData(deviceId = uuid, hostStatePresent = true))
        assertNull(migrated[InstallationPreferences.SESSION])
        assertNull(migrated[InstallationPreferences.PAYMENT_USER])
        assertNull(migrated[InstallationPreferences.PAYMENT_EPOCH])
    }

    @Test fun `absent legacy host preserves a valid core only session`() {
        val current = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.SESSION] = staleSession }
        assertEquals(staleSession, migrateInstallationPreferences(current, LegacyInstallationData())[InstallationPreferences.SESSION])
    }

    @Test fun `ambiguous canonical reports retain facts but cannot send or restore success`() {
        val first = media("result").ensureReports("user", { "report-A" }, uuid)
        val other = first.copy(reports = first.reports.map { it.copy(reportId = "report-B", succeeded = true) })
        val current = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.LEDGER] = Json.encodeToString(first) }
        val migrated = AttributionLedger.decode(migrateInstallationPreferences(current,
            LegacyInstallationData(ledger = Json.encodeToString(other)))[InstallationPreferences.LEDGER])
        assertEquals(setOf("report-A", "report-B"), migrated.reports.map { it.reportId }.toSet())
        assertTrue(migrated.reports.any { it.succeeded })
        assertTrue(migrated.pendingReports("user").isEmpty())
        assertNull(migrated.reportFor("user", "result"))
        assertNull(migrated.completionFor(AttributionCompletionScope("user", uuid, "package", "dev")))
    }
    private fun media(id: String, campaign: String = id): AttributionLedger = AttributionLedger().record(
        AttributionResultSource.GCD_HTTP,
        Json.parseToJsonElement("""{"af_status":"Non-organic","campaign":"$campaign"}""").jsonObject,
        "process", { id },
    ).ledger

    @Test fun `merge selects newer media while preserving older success and both report IDs`() {
        val old = media("first").ensureReports("user", { "first-report" }, uuid).markSucceeded("first-report", 12)
        val newer = old.record(AttributionResultSource.GCD_HTTP,
            Json.parseToJsonElement("""{"af_status":"Non-organic","campaign":"second"}""").jsonObject,
            "new-process", { "second" }).ledger.ensureReports("user", { "second-report" }, uuid)
        for ((existing, legacy) in listOf(old to newer, newer to old)) {
            val preferences = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.LEDGER] = Json.encodeToString(existing) }
            val merged = AttributionLedger.decode(migrateInstallationPreferences(preferences,
                LegacyInstallationData(ledger = Json.encodeToString(legacy), deviceId = uuid))[InstallationPreferences.LEDGER])
            assertEquals("second", merged.latestResultId)
            assertEquals(setOf("first-report", "second-report"), merged.reports.map { it.reportId }.toSet())
            assertTrue(merged.reportFor("user", "first")!!.succeeded)
            assertNull(merged.completionFor(AttributionCompletionScope("user", uuid, "package", "dev")))
        }
    }

    @Test fun `merge keeps media over newer organic without fabricating compatibility snapshot`() {
        val media = media("media")
        val organicBase = AttributionLedger().record(AttributionResultSource.APPSFLYER_SDK,
            Json.parseToJsonElement("""{"af_status":"Organic"}""").jsonObject, "p", { "organic" }).ledger
        val organic = organicBase.copy(results = organicBase.results.map { it.copy(index = 9) })
        for ((existing, legacy) in listOf(media to organic, organic to media)) {
            val preferences = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.LEDGER] = Json.encodeToString(existing) }
            val merged = migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = Json.encodeToString(legacy), attribution = """{"raw":true}"""))
            assertEquals("media", AttributionLedger.decode(merged[InstallationPreferences.LEDGER]).latestResultId)
            assertEquals("""{"raw":true}""", merged[InstallationPreferences.ATTRIBUTION])
        }
    }

    @Test fun `matching report preserves legacy success timestamp and frozen device with Android ID current`() {
        val existing = media("result").ensureReports("owner", { "report" })
        val legacy = existing.copy(reports = existing.reports.map { it.copy(deviceId = uuid, succeeded = true, succeededAtMillis = 42) })
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[InstallationPreferences.LEDGER] = Json.encodeToString(existing)
            this[InstallationPreferences.DEVICE] = "android-id"
        }
        val migrated = migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = Json.encodeToString(legacy), deviceId = uuid))
        val report = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER]).reports.single()
        assertEquals(uuid, report.deviceId)
        assertEquals(42L, report.succeededAtMillis)
        assertTrue(report.succeeded)
        assertEquals("android-id", migrated[InstallationPreferences.DEVICE])
    }

    @Test fun `legacy UUID freezes missing reports for all owners before merge into Android ID store`() {
        val legacy = media("result").ensureReports("one", { "one-report" }).ensureReports("two", { "two-report" })
        val preferences = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.DEVICE] = "android-id" }
        val migrated = migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = Json.encodeToString(legacy), deviceId = uuid))
        assertEquals(listOf(uuid, uuid), AttributionLedger.decode(migrated[InstallationPreferences.LEDGER]).reports.map { it.deviceId })
        assertEquals("android-id", migrated[InstallationPreferences.DEVICE])
    }

    @Test fun `explicit frozen device beats UUID inference for the same report owner in either store`() {
        val missing = media("result").ensureReports("owner", { "report" })
        val frozen = missing.copy(reports = missing.reports.map { it.copy(deviceId = "original-device") })
        for ((existing, legacy) in listOf(missing to frozen, frozen to missing)) {
            val preferences = emptyPreferences().toMutablePreferences().apply {
                this[InstallationPreferences.LEDGER] = Json.encodeToString(existing)
                this[InstallationPreferences.DEVICE] = uuid
            }
            val migrated = migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = Json.encodeToString(legacy), deviceId = uuid))
            assertEquals("original-device", AttributionLedger.decode(migrated[InstallationPreferences.LEDGER]).reports.single().deviceId)
        }
    }

    @Test fun `merge fails closed on corrupt input and conflicting report owner`() {
        val ledger = media("result").ensureReports("one", { "report" }, uuid)
        val preferences = emptyPreferences().toMutablePreferences().apply { this[InstallationPreferences.LEDGER] = Json.encodeToString(ledger) }
        assertThrows(Exception::class.java) { migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = "{broken")) }
        val conflicting = ledger.copy(reports = ledger.reports.map { it.copy(userId = "two") })
        assertThrows(IllegalStateException::class.java) { migrateInstallationPreferences(preferences, LegacyInstallationData(ledger = Json.encodeToString(conflicting))) }
        assertEquals(ledger, AttributionLedger.decode(preferences[InstallationPreferences.LEDGER]))
        assertNull(preferences[InstallationPreferences.MIGRATED])
    }

    @Test fun `migration retains identifiers successes snapshot types and session metadata`() {
        val legacy = LegacyInstallationData(
            ledger = """{"latestResultId":"r","nextResultIndex":8,"results":[{"id":"r","source":"GCD_HTTP","data":{"af_status":"Non-organic","gaid":"secret"},"index":7,"startupId":"old"}],"reports":[{"reportId":"p","resultId":"r","userId":"u","succeeded":true}]}""",
            attribution = """{"raw":true,"count":2,"gaid":"secret"}""",
            deviceId = "old-device",
            session = """{"userId":"u","token":"token","refreshToken":"refresh","sessionId":"server-session","isAnonymous":false,"epoch":"host"}""",
            paymentEpoch = "payment", paymentRevision = 9, paymentUser = "u",
        )
        assertEquals("LegacyInstallationData(redacted)", legacy.toString())
        val migrated = migrateInstallationPreferences(emptyPreferences(), legacy)
        val ledger = AttributionLedger.decode(migrated[InstallationPreferences.LEDGER])
        assertEquals("p", ledger.reports.single().reportId)
        assertTrue(ledger.reports.single().succeeded)
        assertEquals(8L, ledger.nextResultIndex)
        assertFalse(migrated[InstallationPreferences.LEDGER]!!.contains("secret"))
        assertEquals(Json.parseToJsonElement("""{"raw":true,"count":2}"""), Json.parseToJsonElement(migrated[InstallationPreferences.ATTRIBUTION]!!))
        assertTrue(migrated[InstallationPreferences.SESSION]!!.contains("server-session"))
        assertEquals(9L, migrated[InstallationPreferences.PAYMENT_REVISION])
        assertEquals(migrated, migrateInstallationPreferences(migrated, legacy))
    }

    @Test fun `device replacement freezes missing device for every owner`() {
        val uuid = "12345678-1234-4234-8234-123456789abc"
        val raw = """{"reports":[{"reportId":"a","resultId":"r","userId":"a"},{"reportId":"b","resultId":"r","userId":"b","succeeded":true},{"reportId":"c","resultId":"r","userId":"c","deviceId":"frozen"}]}"""
        val data = migrateInstallationPreferences(emptyPreferences(), LegacyInstallationData(ledger = raw, deviceId = uuid)).toMutablePreferences()
        replaceLegacyDeviceId(data, "android-id")
        val reports = AttributionLedger.decode(data[InstallationPreferences.LEDGER]).reports
        assertEquals(listOf(uuid, uuid, "frozen"), reports.map { it.deviceId })
        assertEquals("android-id", data[InstallationPreferences.DEVICE])
    }

    @Test fun `snapshot alone never becomes a source or report`() {
        val migrated = migrateInstallationPreferences(emptyPreferences(), LegacyInstallationData(attribution = """{"af_status":"Non-organic"}"""))
        assertTrue(AttributionLedger.decode(migrated[InstallationPreferences.LEDGER]).results.isEmpty())
    }
}
