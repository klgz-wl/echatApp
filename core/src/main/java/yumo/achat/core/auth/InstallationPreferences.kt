package yumo.achat.core.auth

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import yumo.achat.core.attribution.AttributionLedger
import yumo.achat.core.attribution.AttributionLogicalContext
import yumo.achat.core.attribution.QuarantinedAttributionEvidence
import yumo.achat.core.attribution.StoredAttributionCompletion
import yumo.achat.core.attribution.StoredAttributionResult
import yumo.achat.core.attribution.isMediaAttribution
import yumo.achat.core.attribution.withoutAdvertisingIdentifiers
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

data class InstallationOwner(val deviceId: String, val userId: String?) {
    override fun toString(): String = "InstallationOwner(redacted)"
}

/** One process-wide DataStore instance per configured installation file. No corruption fallback. */
class InstallationPreferences(
    private val data: DataStore<Preferences>,
    private val timeoutMillis: Long = 3000,
) {
    private val publication = synchronized(publications) {
        publications.getOrPut(data) { OwnerPublication(data) }
    }
    init { require(timeoutMillis > 0) }
    constructor(context: Context, storageName: String? = null, timeoutMillis: Long = configuredTimeout(context)) :
        this(sharedStore(context.applicationContext, storageName), timeoutMillis)

    suspend fun read(): Preferences = withTimeout(timeoutMillis) {
        publication.mutex.withLock { data.data.first().also(publication::publish) }
    }
    suspend fun edit(transform: (MutablePreferences) -> Unit): Preferences = withTimeout(timeoutMillis) {
        publication.mutex.withLock { data.edit { transform(it) }.also(publication::publish) }
    }
    fun ownerSnapshot(): InstallationOwner? = publication.owner.get()

    companion object {
        val LEDGER = stringPreferencesKey("install_attribution_ledger_v1")
        val ATTRIBUTION = stringPreferencesKey("appsflyer_install_attribution")
        val DEVICE = stringPreferencesKey("device_id")
        val SESSION = stringPreferencesKey("auth_session")
        val PAYMENT_EPOCH = stringPreferencesKey("core_payment_epoch")
        val PAYMENT_USER = stringPreferencesKey("core_payment_user")
        val PAYMENT_REVISION = longPreferencesKey("core_payment_revision")
        internal val MIGRATED = booleanPreferencesKey("installation_preferences_migrated_v1")
        internal val ATTRIBUTION_CONTEXT = stringPreferencesKey("install_attribution_logical_context_v1")
        private val stores = mutableMapOf<String, DataStore<Preferences>>()
        private val publications = IdentityHashMap<DataStore<Preferences>, OwnerPublication>()
        private fun metadata(context: Context) = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        fun configuredTimeout(context: Context): Long = metadata(context)?.get("attribution.storage.timeout_ms")?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: 3000
        private fun sharedStore(context: Context, storageName: String?): DataStore<Preferences> {
            val name = storageName ?: metadata(context)?.getString("attribution.storage.name") ?: "achat_storage_name"
            require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "Invalid installation storage name" }
            val file = context.preferencesDataStoreFile(name)
            return synchronized(stores) {
                stores.getOrPut(file.canonicalPath) {
                    val attribution = context.getSharedPreferences("achat_attribution", Context.MODE_PRIVATE)
                    val environment = metadata(context)?.getString("attribution.environment")
                    val logicalContext = environment?.let { AttributionLogicalContext(context.packageName, it) }
                    PreferenceDataStoreFactory.create(migrations = listOf(object : DataMigration<Preferences> {
                        override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[MIGRATED] != true ||
                            (logicalContext != null && currentData[ATTRIBUTION_CONTEXT] != Json.encodeToString(logicalContext)) ||
                            legacyAttributionNeedsCleanup(attribution.all)
                        override suspend fun migrate(currentData: Preferences): Preferences {
                            if (currentData[MIGRATED] == true) return migrateInstallationPreferences(
                                currentData, LegacyInstallationData(), context.packageName, environment)
                            val backend = context.getSharedPreferences("achat_backend_session", Context.MODE_PRIVATE)
                            return migrateInstallationPreferences(currentData, LegacyInstallationData(
                                attribution.getString(LEDGER.name, null), attribution.getString(ATTRIBUTION.name, null),
                                backend.getString(DEVICE.name, null), backend.getString(SESSION.name, null),
                                backend.getString(PAYMENT_EPOCH.name, null), backend.getLong(PAYMENT_REVISION.name, 0),
                                backend.getString(PAYMENT_USER.name, null),
                                hostStatePresent = backend.contains(DEVICE.name) || backend.contains(SESSION.name) ||
                                    backend.contains(PAYMENT_EPOCH.name) || backend.contains(PAYMENT_USER.name) || backend.contains(PAYMENT_REVISION.name),
                            ), context.packageName, environment)
                        }
                        override suspend fun cleanUp() {
                            cleanLegacyAttribution(attribution.all) { values ->
                                val editor = attribution.edit()
                                values.forEach { (key, value) -> editor.putString(key, value) }
                                editor.commit()
                            }
                        }
                    })) { file }
                }
            }
        }
    }
}

internal class OwnerPublication(
    private val data: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val mutex = Mutex()
    val owner = AtomicReference<InstallationOwner?>()
    val observation = scope.launch {
        try {
            // Re-read under the same lock as commits so delayed Flow emissions cannot restore an old owner.
            data.data.collect { mutex.withLock { publish(data.data.first()) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Explicit reads/edits still surface failures and can republish after storage recovers.
            owner.set(null)
        }
    }
    fun publish(preferences: Preferences) {
        val device = preferences[InstallationPreferences.DEVICE]?.takeIf { it.isNotBlank() }
        if (device == null) {
            owner.set(null)
            return
        }
        val user = preferences[InstallationPreferences.SESSION]?.let {
            Json.parseToJsonElement(it).jsonObject["userId"]?.jsonPrimitive?.contentOrNull
        }
        owner.set(InstallationOwner(device, user))
    }
}

internal fun legacyAttributionNeedsCleanup(values: Map<String, *>): Boolean =
    sanitizedLegacyAttribution(values).any { (key, value) -> value != values[key] }

internal fun cleanLegacyAttribution(values: Map<String, *>, commit: (Map<String, String>) -> Boolean) {
    val sanitized = sanitizedLegacyAttribution(values).filter { (key, value) -> value != values[key] }
    if (sanitized.isNotEmpty()) check(commit(sanitized)) { "Legacy attribution privacy cleanup failed" }
}

private fun sanitizedLegacyAttribution(values: Map<String, *>): Map<String, String> = buildMap {
    (values[InstallationPreferences.LEDGER.name] as? String)?.let {
        put(InstallationPreferences.LEDGER.name, Json.parseToJsonElement(it).jsonObject.withoutAdvertisingIdentifiers().toString())
    }
    (values[InstallationPreferences.ATTRIBUTION.name] as? String)?.let {
        put(InstallationPreferences.ATTRIBUTION.name, Json.parseToJsonElement(it).jsonObject.withoutAdvertisingIdentifiers().toString())
    }
}

data class LegacyInstallationData(
    val ledger: String? = null, val attribution: String? = null, val deviceId: String? = null,
    val session: String? = null, val paymentEpoch: String? = null, val paymentRevision: Long = 0, val paymentUser: String? = null,
    val hostStatePresent: Boolean = deviceId != null || session != null || paymentEpoch != null || paymentUser != null || paymentRevision != 0L,
) {
    override fun toString(): String = "LegacyInstallationData(redacted)"
}

fun migrateInstallationPreferences(current: Preferences, legacy: LegacyInstallationData,
    packageName: String? = null, environment: String? = null): Preferences {
    val imported = migrateLegacyInstallationPreferences(current, legacy)
    if (packageName == null || environment == null) return imported
    return reconcileAttributionContext(imported, AttributionLogicalContext(packageName, environment))
}

private fun migrateLegacyInstallationPreferences(current: Preferences, legacy: LegacyInstallationData): Preferences {
    if (current[InstallationPreferences.MIGRATED] == true) return current
    val updated = current.toMutablePreferences()
    val hostSession = if (legacy.hostStatePresent) legacy.session?.let { Json.parseToJsonElement(it).jsonObject } else null
    val hostDevice = legacy.deviceId?.takeIf { device -> device.isNotBlank() && hostSession != null &&
        listOf("userId", "token", "refreshToken").all { !hostSession[it]?.jsonPrimitive?.contentOrNull.isNullOrBlank() } }
    val previousDevice = current[InstallationPreferences.DEVICE]
    val installedRaw = AttributionLedger.decode(current[InstallationPreferences.LEDGER])
    val oldRaw = AttributionLedger.decode(legacy.ledger)
    val installed = installedRaw.inheritKnownReportDevices(oldRaw).let { ledger ->
        // Freeze the destination's historical owners before the authoritative host identity replaces it.
        if (hostDevice != null && !previousDevice.isNullOrBlank() && hostDevice != previousDevice)
            ledger.freezeMissingDeviceIds(previousDevice)
        else ledger.freezeLegacyReports(previousDevice)
    }
    val old = oldRaw.inheritKnownReportDevices(installedRaw).freezeLegacyReports(legacy.deviceId)
    val installedResults = installed.results.associateBy { it.id }
    old.results.forEach { result ->
        val existing = installedResults[result.id]
        check(existing == null || existing == result) { "Conflicting attribution result identity" }
    }
    val results = installed.results + old.results.filter { candidate -> installed.results.none { it.id == candidate.id } }
    val reports = installed.reports.map { report ->
        val previous = old.reports.firstOrNull { it.reportId == report.reportId }
        if (previous == null) report else {
            check(report.userId == previous.userId && report.resultId == previous.resultId) { "Conflicting attribution report owner" }
            check(report.deviceId.isBlank() || previous.deviceId.isBlank() || report.deviceId == previous.deviceId) { "Conflicting frozen report device" }
            report.copy(deviceId = report.deviceId.ifBlank { previous.deviceId },
                succeeded = report.succeeded || previous.succeeded,
                succeededAtMillis = report.succeededAtMillis ?: previous.succeededAtMillis,
                legacyCompletionEvidence = (report.legacyCompletionEvidence + previous.legacyCompletionEvidence).distinct())
        }
    } + old.reports.filter { candidate -> installed.reports.none { it.reportId == candidate.reportId } }
    val target = listOfNotNull(installed.currentResult(), old.currentResult())
        .maxWithOrNull(compareBy<StoredAttributionResult> { it.data.isMediaAttribution() }.thenBy { it.index })?.id
    val merged = installed.copy(results = results, reports = reports,
        quarantinedEvidence = (installed.quarantinedEvidence + old.quarantinedEvidence).distinct(),
        quarantinedLegacyCompletionEvidence = (installed.quarantinedLegacyCompletionEvidence + old.quarantinedLegacyCompletionEvidence).distinct(),
        completions = (installed.completions + old.completions).distinct(),
        latestResultId = target,
        nextResultIndex = maxOf(installed.nextResultIndex, old.nextResultIndex, (results.maxOfOrNull { it.index } ?: 0) + 1))
    if (current[InstallationPreferences.LEDGER] != null || legacy.ledger != null) updated[InstallationPreferences.LEDGER] = Json.encodeToString(merged)
    (current[InstallationPreferences.ATTRIBUTION] ?: legacy.attribution)?.let {
        updated[InstallationPreferences.ATTRIBUTION] = Json.parseToJsonElement(it).jsonObject.withoutAdvertisingIdentifiers().toString()
    }
    if (hostDevice != null) updated[InstallationPreferences.DEVICE] = hostDevice
    else if (updated[InstallationPreferences.DEVICE] == null) legacy.deviceId?.let { updated[InstallationPreferences.DEVICE] = it }
    if (legacy.hostStatePresent) {
        updated.remove(InstallationPreferences.SESSION)
        updated.remove(InstallationPreferences.PAYMENT_EPOCH)
        updated.remove(InstallationPreferences.PAYMENT_USER)
        updated.remove(InstallationPreferences.PAYMENT_REVISION)
    }
    hostSession?.let { raw ->
        val revision = if (raw["userId"]?.jsonPrimitive?.contentOrNull == legacy.paymentUser) legacy.paymentRevision else 0L
        updated[InstallationPreferences.SESSION] = JsonObject(raw + ("revision" to (raw["revision"] ?: JsonPrimitive(revision)))).toString()
    }
    if (legacy.hostStatePresent && legacy.session != null) {
        legacy.paymentEpoch?.let { updated[InstallationPreferences.PAYMENT_EPOCH] = it }
        legacy.paymentUser?.let { updated[InstallationPreferences.PAYMENT_USER] = it }
        updated[InstallationPreferences.PAYMENT_REVISION] = legacy.paymentRevision
    }
    updated[InstallationPreferences.MIGRATED] = true
    return updated
}

/**
 * Authentication import is one-time, but a shared installation file can change logical context.
 * Explicit report scope wins over legacy context inference, for every user. Audit never re-enters
 * the active result/dedup/outbox sets; a new observation establishes fresh current-context evidence.
 */
private fun reconcileAttributionContext(current: Preferences, context: AttributionLogicalContext): Preferences {
    val previous = current[InstallationPreferences.ATTRIBUTION_CONTEXT]?.let { Json.decodeFromString<AttributionLogicalContext>(it) }
    val contextChanged = previous != null && previous != context
    val ledger = AttributionLedger.decode(current[InstallationPreferences.LEDGER])
    val evidence = (ledger.completions + ledger.reports.flatMap { it.legacyCompletionEvidence }).distinct()
    fun StoredAttributionCompletion.inCurrentContext() = packageName == context.packageName && environment == context.environment
    val resultEvidence = evidence.groupBy { it.resultId }
    val reportEvidence = evidence.groupBy { Triple(it.userId, it.resultId, it.reportId) }
    val knownCurrentResults = if (previous == context) ledger.reports.filter {
        reportEvidence[Triple(it.userId, it.resultId, it.reportId)].isNullOrEmpty()
    }.map { it.resultId }.toSet() else emptySet()
    val excludedResults = ledger.results.filter { result ->
        val scopes = resultEvidence[result.id].orEmpty()
        if (scopes.isEmpty()) contextChanged else result.id !in knownCurrentResults &&
            scopes.none { it.inCurrentContext() && it.source == result.source }
    }
    val excludedResultIds = excludedResults.map { it.id }.toSet()
    val excludedReports = ledger.reports.filter { report ->
        val scopes = reportEvidence[Triple(report.userId, report.resultId, report.reportId)].orEmpty()
        report.resultId in excludedResultIds || if (scopes.isNotEmpty()) {
            scopes.none { completion -> completion.inCurrentContext() &&
                (ledger.result(report.resultId)?.source?.let { it == completion.source } ?: true) }
        } else {
            contextChanged || (previous == null && resultEvidence[report.resultId].orEmpty().any { !it.inCurrentContext() })
        }
    }
    val excludedReportKeys = excludedReports.map { Triple(it.userId, it.resultId, it.reportId) }.toSet()
    val excludedEvidence = evidence.filter {
        it.resultId in excludedResultIds || Triple(it.userId, it.resultId, it.reportId) in excludedReportKeys
    }
    val changed = contextChanged || excludedResults.isNotEmpty() || excludedReports.isNotEmpty()
    val snapshot = current[InstallationPreferences.ATTRIBUTION]?.let { Json.parseToJsonElement(it).jsonObject.withoutAdvertisingIdentifiers() }
    val results = ledger.results.filterNot { it.id in excludedResultIds }
    val reports = ledger.reports.filterNot { Triple(it.userId, it.resultId, it.reportId) in excludedReportKeys }
    val audit = if (excludedResults.isNotEmpty() || excludedReports.isNotEmpty() || (changed && snapshot != null)) {
        listOf(QuarantinedAttributionEvidence(previous, excludedResults, excludedReports, excludedEvidence, snapshot))
    } else emptyList()
    val target = if (changed) results.maxWithOrNull(compareBy<StoredAttributionResult> { it.data.isMediaAttribution() }.thenBy { it.index })?.id
        else ledger.latestResultId
    val allResults = results + ledger.quarantinedEvidence.flatMap { it.results } + excludedResults
    val reconciled = ledger.copy(
        results = results, reports = reports, latestResultId = target,
        completions = ledger.completions.filterNot { it in excludedEvidence },
        quarantinedLegacyCompletionEvidence = ledger.quarantinedLegacyCompletionEvidence.filterNot { it in excludedEvidence },
        quarantinedEvidence = ledger.quarantinedEvidence + audit,
        nextResultIndex = maxOf(ledger.nextResultIndex, (allResults.maxOfOrNull { it.index } ?: 0) + 1),
    ).sanitized()
    return current.toMutablePreferences().apply {
        if (current[InstallationPreferences.LEDGER] != null || audit.isNotEmpty()) this[InstallationPreferences.LEDGER] = Json.encodeToString(reconciled)
        if (changed) {
            val activeSnapshot = reconciled.currentResult()?.data
            if (activeSnapshot == null) remove(InstallationPreferences.ATTRIBUTION)
            else this[InstallationPreferences.ATTRIBUTION] = activeSnapshot.toString()
        }
        this[InstallationPreferences.ATTRIBUTION_CONTEXT] = Json.encodeToString(context)
    }
}

private fun AttributionLedger.freezeLegacyReports(deviceId: String?): AttributionLedger {
    if (deviceId == null) return this
    val isUuid = runCatching { java.util.UUID.fromString(deviceId).toString().equals(deviceId, ignoreCase = true) }.getOrDefault(false)
    return if (isUuid) freezeMissingDeviceIds(deviceId) else this
}

private fun AttributionLedger.inheritKnownReportDevices(other: AttributionLedger): AttributionLedger = copy(reports = reports.map { report ->
    val previous = other.reports.firstOrNull { it.reportId == report.reportId && it.userId == report.userId && it.resultId == report.resultId }
    if (report.deviceId.isBlank() && previous != null) report.copy(deviceId = previous.deviceId) else report
})

/** Must execute inside the same edit that replaces the installation identity. */
fun replaceLegacyDeviceId(preferences: MutablePreferences, replacement: String) {
    require(replacement.isNotBlank())
    val previous = preferences[InstallationPreferences.DEVICE] ?: error("Missing previous installation identity")
    val ledger = AttributionLedger.decode(preferences[InstallationPreferences.LEDGER]).freezeMissingDeviceIds(previous)
    preferences[InstallationPreferences.LEDGER] = Json.encodeToString(ledger)
    preferences[InstallationPreferences.DEVICE] = replacement
}
