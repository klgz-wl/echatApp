package yumo.achat.app.attribution

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import yumo.achat.core.attribution.AttributionResultSource

class AttributionLedgerTest {
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
}
