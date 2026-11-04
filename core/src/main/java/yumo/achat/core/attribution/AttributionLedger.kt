package yumo.achat.core.attribution

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import yumo.achat.core.auth.InstallationPreferences
import java.util.UUID

@Serializable
data class StoredAttributionResult(val id: String, val source: AttributionResultSource, val data: JsonObject, val index: Long, val startupId: String) {
    override fun toString(): String = "StoredAttributionResult(redacted)"
}

@Serializable
data class StoredAttributionReport(val reportId: String, val resultId: String, val userId: String,
    val deviceId: String = "", val succeeded: Boolean = false, val succeededAtMillis: Long? = null,
    val legacyCompletionEvidence: List<StoredAttributionCompletion> = emptyList()) {
    override fun toString(): String = "StoredAttributionReport(redacted)"
}

@Serializable
data class AttributionCompletionScope(val userId: String, val installDeviceId: String, val packageName: String, val environment: String) {
    init { require(listOf(userId, installDeviceId, packageName, environment).all { it.isNotBlank() }) }
    override fun toString(): String = "AttributionCompletionScope(redacted)"
}

@Serializable
data class StoredAttributionCompletion(val userId: String, val installDeviceId: String, val packageName: String,
    val environment: String, val resultId: String, val reportId: String, val source: AttributionResultSource, val completedAtMillis: Long) {
    override fun toString(): String = "StoredAttributionCompletion(redacted)"
    fun matches(scope: AttributionCompletionScope): Boolean = userId == scope.userId &&
        installDeviceId == scope.installDeviceId && packageName == scope.packageName && environment == scope.environment
}

@Serializable
data class AttributionLogicalContext(val packageName: String, val environment: String) {
    init { require(packageName.isNotBlank() && environment.isNotBlank()) }
}

/** Historical facts from another logical application context; never an active outbox or success marker. */
@Serializable
data class QuarantinedAttributionEvidence(
    val previousContext: AttributionLogicalContext? = null,
    val results: List<StoredAttributionResult> = emptyList(),
    val reports: List<StoredAttributionReport> = emptyList(),
    val legacyCompletionEvidence: List<StoredAttributionCompletion> = emptyList(),
    val compatibilitySnapshot: JsonObject? = null,
) {
    override fun toString(): String = "QuarantinedAttributionEvidence(redacted)"
}

@Serializable
data class AttributionLedger(
    val latestResultId: String? = null,
    val nextResultIndex: Long = 1,
    val results: List<StoredAttributionResult> = emptyList(),
    val reports: List<StoredAttributionReport> = emptyList(),
    @Transient val completions: List<StoredAttributionCompletion> = emptyList(),
    val version: Int = 1,
    // Orphaned audit evidence is never used as a completion marker.
    val quarantinedLegacyCompletionEvidence: List<StoredAttributionCompletion> = emptyList(),
    val quarantinedEvidence: List<QuarantinedAttributionEvidence> = emptyList(),
) {
    override fun toString(): String = "AttributionLedger(redacted)"
    data class RecordResult(val ledger: AttributionLedger, val result: StoredAttributionResult, val created: Boolean)
    fun record(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String): RecordResult {
        val clean = sanitized()
        val payload = data.withoutAdvertisingIdentifiers()
        clean.results.firstOrNull { it.source == source && it.data == payload }?.let { return RecordResult(clean, it, false) }
        val index = maxOf(clean.nextResultIndex, (clean.results.maxOfOrNull { it.index } ?: 0) + 1)
        val result = StoredAttributionResult(id(), source, payload, index, startupId)
        val current = clean.currentResult()
        val select = current == null || payload.isMediaAttribution() || !current.data.isMediaAttribution()
        return RecordResult(clean.copy(latestResultId = if (select) result.id else clean.latestResultId,
            nextResultIndex = index + 1, results = clean.results + result), result, true)
    }
    fun ensureReports(userId: String, reportId: () -> String, deviceId: String = ""): AttributionLedger {
        if (userId.isBlank()) return this
        requireUnambiguousCurrentReport(userId)
        val keys = reports.map { it.userId to it.resultId }.toSet()
        val additions = results.filter { userId to it.id !in keys }.map { StoredAttributionReport(reportId(), it.id, userId, deviceId) }
        val frozen = reports.map { if (it.userId == userId && it.deviceId.isBlank() && isLegacyUuid(deviceId)) it.copy(deviceId = deviceId) else it }
        return copy(reports = frozen + additions)
    }
    internal fun requireUnambiguousCurrentReport(userId: String) {
        val target = latestResultId ?: return
        check(reports.count { it.userId == userId && it.resultId == target } <= 1) {
            "Ambiguous current attribution report identity"
        }
    }
    fun freezeMissingDeviceIds(deviceId: String): AttributionLedger = copy(reports = reports.map {
        if (it.deviceId.isBlank()) it.copy(deviceId = deviceId) else it
    })
    fun markSucceeded(reportId: String, atMillis: Long): AttributionLedger = copy(reports = reports.map {
        if (it.reportId == reportId) it.copy(succeeded = true, succeededAtMillis = it.succeededAtMillis ?: atMillis) else it
    })
    fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope): AttributionLedger {
        if (reports.none { it.reportId == reportId && it.userId == scope.userId }) return this
        return markSucceeded(reportId, atMillis)
    }
    fun completionFor(scope: AttributionCompletionScope): StoredAttributionCompletion? {
        val result = currentResult()?.takeIf { it.data.isMediaAttribution() } ?: return null
        val report = reportFor(scope.userId, result.id)?.takeIf { it.succeeded } ?: return null
        if (report.deviceId.isNotBlank() && report.deviceId != scope.installDeviceId) return null
        val legacyScopes = (completions + report.legacyCompletionEvidence).filter {
            it.reportId == report.reportId && it.resultId == result.id && it.userId == report.userId
        }
        if (legacyScopes.isNotEmpty() && legacyScopes.none { it.matches(scope) }) return null
        return StoredAttributionCompletion(scope.userId, scope.installDeviceId, scope.packageName, scope.environment,
            result.id, report.reportId, result.source, report.succeededAtMillis ?: 0)
    }
    fun migrateCompletion(scope: AttributionCompletionScope): AttributionLedger = this
    fun historicalCompletionFor(scope: AttributionCompletionScope): StoredAttributionCompletion? = completionFor(scope)
    fun currentResult(): StoredAttributionResult? = results.firstOrNull { it.id == latestResultId }
    fun pendingReports(userId: String): List<StoredAttributionReport> = reports.filter {
        it.userId == userId && !it.succeeded && reportFor(userId, it.resultId) != null
    }
        .sortedBy { report -> result(report.resultId)?.index ?: Long.MAX_VALUE }
    fun hasSucceededMedia(userId: String): Boolean = currentResult()?.let { it.data.isMediaAttribution() && reportFor(userId, it.id)?.succeeded == true } == true
    fun result(resultId: String): StoredAttributionResult? = results.firstOrNull { it.id == resultId }
    fun reportFor(userId: String, resultId: String): StoredAttributionReport? = reports
        .filter { it.userId == userId && it.resultId == resultId }.singleOrNull()
    fun sanitized(): AttributionLedger = copy(
        results = results.map { it.copy(data = it.data.withoutAdvertisingIdentifiers()) },
        quarantinedEvidence = quarantinedEvidence.map { audit -> audit.copy(
            results = audit.results.map { it.copy(data = it.data.withoutAdvertisingIdentifiers()) },
            compatibilitySnapshot = audit.compatibilitySnapshot?.withoutAdvertisingIdentifiers(),
        ) },
    )
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun empty() = AttributionLedger()
        fun decode(raw: String?): AttributionLedger {
            if (raw == null) return empty()
            val root = json.parseToJsonElement(raw).jsonObject.toMutableMap()
            val legacyCompletions = root["completions"]?.jsonArray?.map { entry ->
                val value = entry.jsonObject.toMutableMap()
                val source = value["source"]?.jsonPrimitive?.content
                AttributionResultSource.entries.firstOrNull { it.name == source || it.wireName == source }?.let {
                    value["source"] = Json.encodeToJsonElement(it)
                }
                json.decodeFromJsonElement<StoredAttributionCompletion>(JsonObject(value))
            }.orEmpty()
            // Legacy apps used uppercase enum names; retain their IDs while normalizing the wire shape.
            root["results"]?.jsonArray?.let { values ->
                root["results"] = JsonArray(values.map { entry -> JsonObject(entry.jsonObject.toMutableMap().apply {
                    val source = get("source")?.jsonPrimitive?.content
                    AttributionResultSource.entries.firstOrNull { it.name == source || it.wireName == source }?.let {
                        put("source", Json.encodeToJsonElement(it))
                    }
                }) })
            }
            root["reports"]?.jsonArray?.let { values ->
                root["reports"] = JsonArray(values.map { entry -> JsonObject(entry.jsonObject.toMutableMap().apply {
                    if (get("deviceId") == JsonNull) remove("deviceId")
                }) })
            }
            val decoded = json.decodeFromJsonElement<AttributionLedger>(JsonObject(root)).also { require(it.version == 1) }
            val evidence = (decoded.quarantinedLegacyCompletionEvidence + legacyCompletions +
                decoded.reports.flatMap { it.legacyCompletionEvidence }).distinct()
            val reports = decoded.reports.map { report ->
                val ownedEvidence = evidence.filter { it.reportId == report.reportId && it.resultId == report.resultId && it.userId == report.userId }
                val completion = ownedEvidence.firstOrNull { value ->
                    value.reportId == report.reportId && value.resultId == report.resultId &&
                        value.userId == report.userId && value.installDeviceId.isNotBlank() && report.succeeded &&
                        decoded.result(report.resultId)?.source == value.source
                }
                report.copy(deviceId = if (report.deviceId.isBlank() && completion != null) completion.installDeviceId else report.deviceId,
                    legacyCompletionEvidence = ownedEvidence)
            }
            val orphaned = evidence.filter { value -> reports.none {
                it.reportId == value.reportId && it.resultId == value.resultId && it.userId == value.userId
            } }
            return decoded.copy(reports = reports, completions = evidence, quarantinedLegacyCompletionEvidence = orphaned).sanitized()
        }
        private fun isLegacyUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)
    }
}

interface AttributionLedgerStore {
    fun snapshot(): AttributionLedger
    fun record(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String): StoredAttributionResult
    fun recordGuarded(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String,
        commitAllowed: () -> Boolean): StoredAttributionResult {
        if (!commitAllowed()) throw CancellationException("Attribution acquisition superseded")
        return record(source, data, startupId, id)
    }
    fun ensureReports(userId: String, reportId: () -> String, deviceId: String = ""): AttributionLedger
    fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope)
    fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion?
    fun restoreCompletion(scope: AttributionCompletionScope,
        onRestored: (StoredAttributionCompletion) -> Unit): StoredAttributionCompletion? = completion(scope)?.also(onRestored)
}

class DataStoreAttributionLedgerStore(
    private val installation: InstallationPreferences,
    private val scopeMatches: (AttributionCompletionScope) -> Boolean = { true },
) : AttributionLedgerStore, AttributionStorage {
    private data class ResultKey(val source: AttributionResultSource, val data: JsonObject)
    private val candidatesLock = Any()
    private val resultCandidates = mutableMapOf<ResultKey, String>()
    private val reportCandidates = mutableMapOf<Pair<String, String>, String>()
    constructor(context: Context, storageName: String? = null, timeoutMillis: Long = 3000) : this(
        InstallationPreferences(context, storageName, timeoutMillis),
        { scope -> scope.packageName == context.packageName && scope.environment ==
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString("attribution.environment") },
    )
    override fun snapshot(): AttributionLedger = blocking { AttributionLedger.decode(installation.read()[InstallationPreferences.LEDGER]) }
    override fun record(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String): StoredAttributionResult =
        recordGuarded(source, data, startupId, id) { true }

    override fun recordGuarded(source: AttributionResultSource, data: JsonObject, startupId: String, id: () -> String,
        commitAllowed: () -> Boolean): StoredAttributionResult = blocking {
        val payload = data.withoutAdvertisingIdentifiers()
        val key = ResultKey(source, payload)
        val before = installation.read()
        val disk = AttributionLedger.decode(before[InstallationPreferences.LEDGER])
        val candidate = synchronized(candidatesLock) {
            disk.results.firstOrNull { it.source == source && it.data == payload }?.id
                ?: resultCandidates.getOrPut(key, id)
        }
        val user = userId(before)
        val preparedReports = prepareReports(disk.results.map { it.id } + candidate, user, disk) { UUID.randomUUID().toString() }
        var result: StoredAttributionResult? = null
        val saved = installation.edit { prefs ->
            if (!commitAllowed()) throw CancellationException("Attribution acquisition superseded")
            check(userId(prefs) == user) { "Attribution owner changed before commit; retry prepared candidate" }
            val recorded = AttributionLedger.decode(prefs[InstallationPreferences.LEDGER]).record(source, payload, startupId) { candidate }
            val ledger = withPreparedReports(recorded.ledger, user, preparedReports, prefs[InstallationPreferences.DEVICE].orEmpty())
            if (!commitAllowed()) throw CancellationException("Attribution acquisition superseded")
            prefs[InstallationPreferences.LEDGER] = Json.encodeToString(ledger)
            prefs[InstallationPreferences.ATTRIBUTION] = (ledger.currentResult()?.data ?: payload).withoutAdvertisingIdentifiers().toString()
            result = recorded.result
        }
        forgetDurableCandidates(AttributionLedger.decode(saved[InstallationPreferences.LEDGER]))
        checkNotNull(result)
    }
    override fun ensureReports(userId: String, reportId: () -> String, deviceId: String): AttributionLedger = blocking {
        val before = AttributionLedger.decode(installation.read()[InstallationPreferences.LEDGER])
        val prepared = prepareReports(before.results.map { it.id }, userId, before, reportId)
        val saved = installation.edit { prefs ->
            val ledger = AttributionLedger.decode(prefs[InstallationPreferences.LEDGER])
            if (userId.isNotBlank()) ledger.requireUnambiguousCurrentReport(userId)
            prefs[InstallationPreferences.LEDGER] = Json.encodeToString(withPreparedReports(
                ledger, userId, prepared, deviceId))
        }
        AttributionLedger.decode(saved[InstallationPreferences.LEDGER]).also(::forgetDurableCandidates)
    }
    override fun markReportSucceeded(reportId: String, atMillis: Long, scope: AttributionCompletionScope) { blocking {
        installation.edit { prefs -> prefs[InstallationPreferences.LEDGER] = Json.encodeToString(
            AttributionLedger.decode(prefs[InstallationPreferences.LEDGER]).markReportSucceeded(reportId, atMillis, scope)) }
    } }
    override fun completion(scope: AttributionCompletionScope): StoredAttributionCompletion? = blocking {
        eligibleCompletion(installation.read(), scope)
    }
    override fun restoreCompletion(scope: AttributionCompletionScope,
        onRestored: (StoredAttributionCompletion) -> Unit): StoredAttributionCompletion? = blocking {
        var restored: StoredAttributionCompletion? = null
        installation.edit { prefs ->
            restored = eligibleCompletion(prefs, scope)
            restored?.let(onRestored)
        }
        restored
    }
    private fun eligibleCompletion(prefs: androidx.datastore.preferences.core.Preferences,
        scope: AttributionCompletionScope): StoredAttributionCompletion? {
        if (!scopeMatches(scope)) return null
        if (prefs[InstallationPreferences.DEVICE] != scope.installDeviceId) return null
        if (userId(prefs) != scope.userId) return null
        return AttributionLedger.decode(prefs[InstallationPreferences.LEDGER]).completionFor(scope)
    }
    private fun userId(prefs: androidx.datastore.preferences.core.Preferences): String? =
        prefs[InstallationPreferences.SESSION]?.let { Json.parseToJsonElement(it).jsonObject["userId"]?.jsonPrimitive?.contentOrNull }

    private fun prepareReports(results: List<String>, user: String?, disk: AttributionLedger, id: () -> String): Map<String, String> {
        if (user.isNullOrBlank()) return emptyMap()
        return synchronized(candidatesLock) {
            results.distinct().associateWith { result ->
                disk.reports.firstOrNull { it.userId == user && it.resultId == result }?.reportId
                    ?: reportCandidates.getOrPut(user to result, id)
            }
        }
    }
    private fun withPreparedReports(ledger: AttributionLedger, user: String?, prepared: Map<String, String>, device: String): AttributionLedger {
        if (user.isNullOrBlank()) return ledger
        val existing = ledger.reports.filter { it.userId == user }.map { it.resultId }.toSet()
        val missing = ledger.results.filter { it.id !in existing }
        val additions = missing.map { result -> StoredAttributionReport(
            checkNotNull(prepared[result.id]) { "Attribution results changed before commit; retry prepared reports" }, result.id, user, device) }
        val frozen = ledger.reports.map {
            if (it.userId == user && it.deviceId.isBlank() && runCatching { UUID.fromString(device).toString().equals(device, true) }.getOrDefault(false))
                it.copy(deviceId = device) else it
        }
        return ledger.copy(reports = frozen + additions)
    }
    private fun forgetDurableCandidates(ledger: AttributionLedger) = synchronized(candidatesLock) {
        resultCandidates.keys.removeAll { key -> ledger.results.any { it.source == key.source && it.data == key.data } }
        reportCandidates.keys.removeAll { (user, result) -> ledger.reports.any { it.userId == user && it.resultId == result } }
    }
    override suspend fun readAttribution(): JsonObject? = installation.read()[InstallationPreferences.ATTRIBUTION]?.let {
        Json.parseToJsonElement(it).jsonObject.withoutAdvertisingIdentifiers()
    }
    override suspend fun writeAttribution(data: JsonObject) {
        installation.edit { prefs ->
            val current = AttributionLedger.decode(prefs[InstallationPreferences.LEDGER]).currentResult()?.data
            prefs[InstallationPreferences.ATTRIBUTION] = (current ?: data).withoutAdvertisingIdentifiers().toString()
        }
    }
    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
