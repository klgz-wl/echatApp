package yumo.achat.app.attribution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.AdvertisingIdProvider
import kotlinx.coroutines.runBlocking
import yumo.achat.core.config.ClientIdentity

class AttributionLedgerTest {
    @Test fun `GAID lookup failure keeps completed login available`() = runBlocking {
        val result = loginAttributionWithGaid("install", AdvertisingIdProvider { error("unavailable") }) {
            completedLoginAttribution(ClientIdentity("test.package", "1", 30), "install")
        }
        assertEquals("install", result.deviceId)
        assertEquals(null, result.gaid)
    }
    @Test fun `completed login adds one fresh GAID without changing attribution result`() = runBlocking {
        var reads = 0
        val base = completedLoginAttribution(ClientIdentity("yumo.achat.app", "1", 30), "install")
        val result = loginAttributionWithGaid("install", AdvertisingIdProvider {
            reads++
            "12345678-1234-4234-8234-123456789abc"
        }) { base }
        assertEquals(1, reads)
        assertEquals("12345678-1234-4234-8234-123456789abc", result.gaid)
        assertEquals(result.gaid, result.deviceId)
        assertEquals(base.extraData, result.extraData)
    }
    private val scope = AttributionCompletionScope(
        userId = "user-1",
        installDeviceId = "install-1",
        packageName = "yumo.achat.app",
        environment = "dev",
    )

    @Test fun `ledger has a generated serializer for durable storage`() {
        val data = buildJsonObject { put("af_status", "Organic") }
        val ledger = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, data, "startup", id = { "result" }).ledger

        val encoded = Json.encodeToString(ledger)

        assertEquals(ledger, Json.decodeFromString<AttributionLedger>(encoded))
    }

    @Test fun `non empty corrupt ledger is not treated as a fresh install`() {
        assertThrows(Exception::class.java) { AttributionLedger.decode("{broken") }
    }
    @Test fun `same source and content reuse result while different source gets next index`() {
        var ids = 0
        val ledger = AttributionLedger.empty()
        val data = buildJsonObject { put("af_status", "Organic") }

        val first = ledger.record(AttributionResultSource.APPSFLYER_SDK, data, "startup", id = { "id-${++ids}" })
        val duplicate = first.ledger.record(AttributionResultSource.APPSFLYER_SDK, data, "startup", id = { "id-${++ids}" })
        val gcd = duplicate.ledger.record(AttributionResultSource.GCD_HTTP, data, "startup", id = { "id-${++ids}" })

        assertEquals(first.result.id, duplicate.result.id)
        assertEquals(1, duplicate.ledger.results.size)
        assertEquals(2, gcd.ledger.results.size)
        assertEquals(2, gcd.result.index)
    }

    @Test fun `per user report id is stable across retries and success persists`() {
        var ids = 0
        val data = buildJsonObject { put("af_status", "Non-organic") }
        val recorded = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, data, "startup", id = { "id-${++ids}" }).ledger
        val prepared = recorded.ensureReports("user-1", reportId = { "report-${++ids}" })
        val repeated = prepared.ensureReports("user-1", reportId = { "report-${++ids}" })
        val report = repeated.pendingReports("user-1").single()

        assertEquals("report-2", report.reportId)
        val succeeded = repeated.markSucceeded(report.reportId, 1234)
        assertTrue(succeeded.reports.single().succeeded)
        assertFalse(succeeded.pendingReports("user-1").isNotEmpty())
        assertEquals("report-2", succeeded.ensureReports("user-1", reportId = { "new" }).reports.single().reportId)
    }

    @Test fun `media success is scoped to stable user`() {
        val data = buildJsonObject { put("af_status", "Non-organic") }
        val recorded = AttributionLedger.empty()
            .record(AttributionResultSource.GCD_HTTP, data, "startup", id = { "result" }).ledger
            .ensureReports("user-1", reportId = { "report-1" })
            .markSucceeded("report-1", 1)

        assertTrue(recorded.hasSucceededMedia("user-1"))
        assertFalse(recorded.hasSucceededMedia("user-2"))
    }

    @Test fun `media report success atomically creates a scoped final completion`() {
        val media = buildJsonObject { put("af_status", "Non-organic"); put("af_uid", "af-1") }
        val prepared = AttributionLedger.empty()
            .record(AttributionResultSource.GCD_HTTP, media, "startup", id = { "result-1" }).ledger
            .ensureReports(scope.userId, reportId = { "report-1" })

        val completed = prepared.markReportSucceeded("report-1", 1234, scope)

        assertTrue(completed.reports.single().succeeded)
        assertEquals("result-1", completed.completionFor(scope)?.resultId)
        assertEquals("report-1", completed.completionFor(scope)?.reportId)
        assertEquals(AttributionResultSource.GCD_HTTP, completed.completionFor(scope)?.source)
    }

    @Test fun `organic remote success does not complete attribution`() {
        val organic = buildJsonObject { put("af_status", "Organic") }
        val completed = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, organic, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report" })
            .markReportSucceeded("report", 1, scope)

        assertEquals(null, completed.completionFor(scope))
        assertTrue(completed.reports.single().succeeded)
    }

    @Test fun `completion cannot cross user install package or environment`() {
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val completed = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, media, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report" })
            .markReportSucceeded("report", 1, scope)

        assertEquals(null, completed.completionFor(scope.copy(userId = "user-2")))
        assertEquals(null, completed.completionFor(scope.copy(installDeviceId = "install-2")))
        assertEquals(null, completed.completionFor(scope.copy(packageName = "com.zorv.app")))
        assertEquals(null, completed.completionFor(scope.copy(environment = "prod")))
    }

    @Test fun `historical succeeded media report migrates to explicit completion`() {
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val legacy = AttributionLedger.empty()
            .record(AttributionResultSource.GCD_HTTP, media, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report" })
            .markSucceeded("report", 99)

        val migrated = legacy.migrateCompletion(scope)

        assertEquals("report", migrated.completionFor(scope)?.reportId)
        assertEquals(99L, migrated.completionFor(scope)?.completedAtMillis)
    }

    @Test fun `multiple stable users retain independent completion records`() {
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val userTwo = scope.copy(userId = "user-2")
        val result = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, media, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report-1" })
            .ensureReports(userTwo.userId, reportId = { "report-2" })
            .markReportSucceeded("report-1", 1, scope)
            .markReportSucceeded("report-2", 2, userTwo)

        assertEquals("report-1", result.completionFor(scope)?.reportId)
        assertEquals("report-2", result.completionFor(userTwo)?.reportId)
    }

    @Test fun `late completion for old account does not match the active account scope`() {
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val completed = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, media, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report" })
            .markReportSucceeded("report", 1, scope)
            .completionFor(scope)!!

        assertTrue(completed.matches(scope))
        assertFalse(completed.matches(scope.copy(userId = "new-user")))
    }

    @Test fun `late old user success cannot activate a different current user`() {
        val media = buildJsonObject { put("af_status", "Non-organic") }
        val ledger = AttributionLedger.empty()
            .record(AttributionResultSource.APPSFLYER_SDK, media, "startup", id = { "result" }).ledger
            .ensureReports(scope.userId, reportId = { "report" })
            .markReportSucceeded("report", 1, scope)
        val completion = ledger.completionFor(scope)
        val result = ledger.result("result")!!

        assertTrue(shouldActivateCompletion(scope.userId, completion, result))
        assertFalse(shouldActivateCompletion("new-user", completion, result))
    }

    @Test fun `completed login uses required empty attribution shape`() {
        val value = completedLoginAttribution(
            ClientIdentity("yumo.achat.app", "1.0", 30),
            installDeviceId = "install",
        )

        assertEquals("install", value.deviceId)
        assertEquals("", value.afUid)
        assertEquals(null, value.network)
        assertTrue(value.extraData.isEmpty())
    }
}
