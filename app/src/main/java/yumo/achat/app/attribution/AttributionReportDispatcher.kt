package yumo.achat.app.attribution

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import yumo.achat.core.attribution.AttributionReportMetadata
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.AttributionStage
import yumo.achat.core.backend.AuthSession

internal class AttributionReportExhaustedException : Exception("Attribution report retries exhausted")
internal class AttributionStorageException : Exception("Attribution persistence unavailable")

internal data class LedgerReportReceipt(
    val epoch: String,
    val userId: String,
    val resultId: String,
    val reportId: String,
    val reportedAtNanos: Long?,
)

internal class LedgerAttributionReportDispatcher(
    private val attempts: Int,
    private val scope: CoroutineScope,
    private val store: AttributionLedgerStore,
    private val startupId: String,
    private val startupNanos: Long,
    private val retryDelay: suspend () -> Unit,
    private val report: suspend (AuthSession, StoredAttributionResult, AttributionReportMetadata) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val drainMutex = Mutex()
    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    private var rerunRequested = false
    private val deferredReports = mutableSetOf<String>()
    @Volatile private var session: AuthSession? = null
    @Volatile private var stage = AttributionStage.STARTUP_RECOVERY
    private val processSuccessNanos = mutableMapOf<String, Long>()
    private val mutableReceipts = MutableStateFlow<LedgerReportReceipt?>(null)
    val receipts = mutableReceipts.asStateFlow()
    private val mutableExhausted = MutableStateFlow<Set<String>>(emptySet())
    val exhausted = mutableExhausted.asStateFlow()

    init { require(attempts > 0) }

    fun updateStage(value: AttributionStage) { stage = value }

    fun updateSession(value: AuthSession) {
        session = value
        try {
            store.ensureReports(value.userId, newId)
            publishPersistedReceipt(value)
        } catch (_: Exception) {
            mutableExhausted.value = setOf(STORAGE_FAILURE_KEY)
        }
        kick()
    }

    fun updateResult(source: AttributionResultSource, data: JsonObject) {
        store.record(source, data, startupId, newId)
        session?.let { store.ensureReports(it.userId, newId) }
        kick()
    }

    fun retryPending() {
        synchronized(stateLock) {
            deferredReports.clear()
            mutableExhausted.value = emptySet()
        }
        kick()
    }

    fun currentResult(): StoredAttributionResult? = runCatching { store.snapshot().currentResult() }.getOrNull()
    fun hasResult(source: AttributionResultSource, data: JsonObject): Boolean =
        runCatching { store.snapshot().results.any { it.source == source && it.data == data } }.getOrDefault(false)
    fun hasSucceededMedia(userId: String): Boolean =
        runCatching { store.snapshot().hasSucceededMedia(userId) }.getOrDefault(false)
    fun storageFailed(): Boolean = STORAGE_FAILURE_KEY in exhausted.value

    private fun kick() {
        if (!running.compareAndSet(false, true)) {
            synchronized(stateLock) { rerunRequested = true }
            return
        }
        scope.launch {
            try {
                do {
                    synchronized(stateLock) { rerunRequested = false }
                    drainMutex.withLock { drain() }
                } while (synchronized(stateLock) { rerunRequested })
            } finally {
                running.set(false)
                if (synchronized(stateLock) { rerunRequested }) kick()
            }
        }
    }

    private suspend fun drain() {
        val active = session ?: return
        val ledger = try {
            store.ensureReports(active.userId, newId)
        } catch (_: Exception) {
            mutableExhausted.value = setOf(STORAGE_FAILURE_KEY)
            return
        }
        for (pending in ledger.pendingReports(active.userId).filterNot { it.reportId in deferredReports }) {
            val result = store.snapshot().result(pending.resultId) ?: continue
            var sent = false
            for (attempt in 0 until attempts) {
                try {
                    report(
                        active,
                        result,
                        AttributionReportMetadata(
                            reportId = pending.reportId,
                            startupElapsedMillis = ((nowNanos() - startupNanos).coerceAtLeast(0L) / 1_000_000L),
                            resultIndex = result.index,
                            stage = if (result.startupId == startupId) stage else AttributionStage.STARTUP_RECOVERY,
                            source = result.source,
                        ),
                    )
                    sent = true
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (attempt + 1 < attempts) retryDelay()
                }
            }
            if (!sent) {
                synchronized(stateLock) { deferredReports += pending.reportId }
                mutableExhausted.value = mutableExhausted.value + pending.reportId
                continue
            }
            val successNanos = nowNanos()
            try {
                store.markSucceeded(pending.reportId, nowMillis())
            } catch (_: Exception) {
                synchronized(stateLock) { deferredReports += pending.reportId }
                mutableExhausted.value = mutableExhausted.value + pending.reportId
                continue
            }
            processSuccessNanos[pending.reportId] = successNanos
            if (session?.epoch == active.epoch) {
                mutableReceipts.value = LedgerReportReceipt(
                    active.epoch, active.userId, result.id, pending.reportId, successNanos,
                )
            }
        }
        publishPersistedReceipt(active)
    }

    private fun publishPersistedReceipt(active: AuthSession) {
        val ledger = store.snapshot()
        val result = ledger.currentResult() ?: return
        val report = ledger.reportFor(active.userId, result.id)?.takeIf { it.succeeded } ?: return
        mutableReceipts.value = LedgerReportReceipt(
            active.epoch, active.userId, result.id, report.reportId, processSuccessNanos[report.reportId],
        )
    }

    fun isExhausted(userId: String, resultId: String): Boolean {
        if (STORAGE_FAILURE_KEY in exhausted.value) return true
        val report = runCatching { store.snapshot().reportFor(userId, resultId) }.getOrNull() ?: return false
        return report.reportId in exhausted.value
    }

    private companion object { const val STORAGE_FAILURE_KEY = "storage" }
}
