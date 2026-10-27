package yumo.achat.app.attribution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.content.SharedPreferences
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.isMediaAttribution

@Serializable
internal data class StoredAttributionResult(
    val id: String,
    val source: AttributionResultSource,
    val data: JsonObject,
    val index: Long,
    val startupId: String,
)

@Serializable
internal data class StoredAttributionReport(
    val reportId: String,
    val resultId: String,
    val userId: String,
    val succeeded: Boolean = false,
    val succeededAtMillis: Long? = null,
)

@Serializable
internal data class AttributionCompletionScope(
    val userId: String,
    val installDeviceId: String,
    val packageName: String,
    val environment: String,
) {
    init {
        require(userId.isNotBlank())
        require(installDeviceId.isNotBlank())
        require(packageName.isNotBlank())
        require(environment.isNotBlank())
    }
}

@Serializable
internal data class StoredAttributionCompletion(
    val userId: String,
    val installDeviceId: String,
    val packageName: String,
    val environment: String,
    val resultId: String,
    val reportId: String,
    val source: AttributionResultSource,
    val completedAtMillis: Long,
) {
    fun matches(scope: AttributionCompletionScope): Boolean =
        userId == scope.userId &&
            installDeviceId == scope.installDeviceId &&
            packageName == scope.packageName &&
            environment == scope.environment
}

@Serializable
internal data class AttributionLedger(
    val latestResultId: String? = null,
    val nextResultIndex: Long = 1,
    val results: List<StoredAttributionResult> = emptyList(),
    val reports: List<StoredAttributionReport> = emptyList(),
    val completions: List<StoredAttributionCompletion> = emptyList(),
) {
    data class RecordResult(val ledger: AttributionLedger, val result: StoredAttributionResult, val created: Boolean)

    fun record(
        source: AttributionResultSource,
        data: JsonObject,
        startupId: String,
        id: () -> String,
    ): RecordResult {
        val existing = results.firstOrNull { it.source == source && it.data == data }
        if (existing != null) return RecordResult(this, existing, false)
        val result = StoredAttributionResult(id(), source, data, nextResultIndex, startupId)
        val current = currentResult()
        val shouldSelect = current == null || result.data.isMediaAttribution() || !current.data.isMediaAttribution()
        val updated = copy(
                latestResultId = if (shouldSelect) result.id else latestResultId,
                nextResultIndex = nextResultIndex + 1,
                results = results + result,
            )
        return RecordResult(
            updated,
            result,
            true,
        )
    }

    fun ensureReports(userId: String, reportId: () -> String): AttributionLedger {
        if (userId.isBlank()) return this
        val existing = reports.map { it.userId to it.resultId }.toSet()
        val additions = results.filter { userId to it.id !in existing }.map {
            StoredAttributionReport(reportId(), it.id, userId)
        }
        return if (additions.isEmpty()) this else copy(reports = reports + additions)
    }

    fun markSucceeded(reportId: String, atMillis: Long): AttributionLedger = copy(
        reports = reports.map {
            if (it.reportId == reportId) it.copy(succeeded = true, succeededAtMillis = atMillis) else it
        },
    )

    fun markReportSucceeded(
        reportId: String,
        atMillis: Long,
        scope: AttributionCompletionScope,
    ): AttributionLedger {
        val report = reports.firstOrNull { it.reportId == reportId } ?: return this
        val result = result(report.resultId) ?: return this
        val succeeded = markSucceeded(reportId, atMillis)
        if (report.userId != scope.userId || !result.data.isMediaAttribution()) return succeeded
        val completed = StoredAttributionCompletion(
                userId = scope.userId,
                installDeviceId = scope.installDeviceId,
                packageName = scope.packageName,
                environment = scope.environment,
                resultId = result.id,
                reportId = report.reportId,
                source = result.source,
                completedAtMillis = atMillis,
            )
        return succeeded.copy(
            completions = succeeded.completions.filterNot { value ->
                value.userId == scope.userId &&
                    value.installDeviceId == scope.installDeviceId &&
                    value.packageName == scope.packageName &&
                    value.environment == scope.environment
            } + completed,
        )
    }

    fun completionFor(scope: AttributionCompletionScope): StoredAttributionCompletion? = completions.firstOrNull { value ->
        value.matches(scope) &&
            result(value.resultId)?.let { result ->
                result.source == value.source && result.data.isMediaAttribution()
            } == true &&
            reports.any { report ->
                report.reportId == value.reportId && report.resultId == value.resultId &&
                    report.userId == value.userId && report.succeeded
            }
    }

    fun migrateCompletion(scope: AttributionCompletionScope): AttributionLedger {
        if (completionFor(scope) != null) return this
        val historical = historicalCompletionFor(scope) ?: return this
        return copy(completions = completions + historical)
    }

    fun historicalCompletionFor(scope: AttributionCompletionScope): StoredAttributionCompletion? {
        val candidate = reports.asSequence()
            .filter { it.userId == scope.userId && it.succeeded }
            .mapNotNull { report -> result(report.resultId)?.let { result -> report to result } }
            .filter { (_, result) -> result.data.isMediaAttribution() }
            .maxByOrNull { (_, result) -> result.index }
            ?: return null
        val (report, result) = candidate
        return StoredAttributionCompletion(
            userId = scope.userId,
            installDeviceId = scope.installDeviceId,
            packageName = scope.packageName,
            environment = scope.environment,
            resultId = result.id,
            reportId = report.reportId,
            source = result.source,
            completedAtMillis = report.succeededAtMillis ?: 0L,
        )
    }

    fun currentResult(): StoredAttributionResult? =
        results.firstOrNull { it.id == latestResultId } ?: results.maxByOrNull { it.index }

    fun pendingReports(userId: String): List<StoredAttributionReport> =
        reports.filter { it.userId == userId && !it.succeeded }
            .sortedBy { report -> results.firstOrNull { it.id == report.resultId }?.index ?: Long.MAX_VALUE }

    fun hasSucceededMedia(userId: String): Boolean = reports.any { report ->
        report.userId == userId && report.succeeded &&
            results.firstOrNull { it.id == report.resultId }?.data?.isMediaAttribution() == true
    }

    fun result(resultId: String): StoredAttributionResult? = results.firstOrNull { it.id == resultId }
    fun reportFor(userId: String, resultId: String): StoredAttributionReport? =
        reports.firstOrNull { it.userId == userId && it.resultId == resultId }

    companion object {
        fun empty() = AttributionLedger()
        fun decode(raw: String?): AttributionLedger {
            if (raw.isNullOrBlank()) return empty()
            return Json { ignoreUnknownKeys = true }.decodeFromString(raw)
        }
    }
}

internal interface AttributionLedgerStore {
    fun snapshot(): AttributionLedger
    fun record(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String): StoredAttributionResult
    fun ensureReports(userId: String, reportId: () -> String): AttributionLedger
    fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope)
    fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion?
}

internal class SharedPreferencesAttributionLedgerStore(
    private val preferences: SharedPreferences,
) : AttributionLedgerStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rawLedger = preferences.getString(KEY_LEDGER, null)
    private val decoded = runCatching { AttributionLedger.decode(rawLedger) }
    private val loadFailure = decoded.exceptionOrNull()
    private var ledger = decoded.getOrDefault(AttributionLedger.empty())

    override fun snapshot(): AttributionLedger = synchronized(lock) { checkHealthy(); ledger }

    override fun record(
        source: AttributionResultSource,
        data: JsonObject,
        startupId: String,
        id: () -> String,
    ): StoredAttributionResult = synchronized(lock) {
        checkHealthy()
        val recorded = ledger.record(source, data, startupId, id)
        if (recorded.created) persist(recorded.ledger)
        recorded.result
    }

    override fun ensureReports(userId: String, reportId: () -> String): AttributionLedger = synchronized(lock) {
        checkHealthy()
        val updated = ledger.ensureReports(userId, reportId)
        if (updated != ledger) persist(updated)
        ledger
    }

    override fun markReportSucceeded(
        reportId: String,
        atMillis: Long,
        scope: AttributionCompletionScope,
    ) = synchronized(lock) {
        checkHealthy()
        persist(ledger.markReportSucceeded(reportId, atMillis, scope))
    }

    override fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion? = synchronized(lock) {
        checkHealthy()
        val migrated = ledger.migrateCompletion(scope)
        if (migrated != ledger) persist(migrated)
        ledger.completionFor(scope)
    }

    private fun persist(updated: AttributionLedger) {
        check(preferences.edit().putString(KEY_LEDGER, json.encodeToString(updated)).commit()) {
            "Attribution ledger persistence failed"
        }
        ledger = updated
    }

    private fun checkHealthy() {
        check(loadFailure == null) { "Attribution ledger is corrupt" }
    }

    private companion object { const val KEY_LEDGER = "install_attribution_ledger_v1" }
}
