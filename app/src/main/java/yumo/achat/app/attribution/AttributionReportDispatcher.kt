package yumo.achat.app.attribution

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import yumo.achat.core.backend.AuthSession
import java.security.MessageDigest

private const val ATTRIBUTION_REPORT_TAG = "AppsFlyer/Attribution"

internal data class ReportReceipt(val epoch: String, val snapshot: JsonObject, val reportedAtNanos: Long)
internal class AttributionReportExhaustedException : Exception("Attribution report retries exhausted")

internal interface AttributionDeliveryStore {
    fun wasReported(digest: String): Boolean
    fun markReported(digest: String): Boolean
}

internal class MemoryAttributionDeliveryStore : AttributionDeliveryStore {
    private val values = mutableSetOf<String>()
    override fun wasReported(digest: String): Boolean = synchronized(values) { digest in values }
    override fun markReported(digest: String): Boolean = synchronized(values) { values.add(digest); true }
}

internal class AttributionReportDispatcher(
    private val attempts: Int,
    private val scope: CoroutineScope,
    private val retryDelay: suspend () -> Unit,
    private val report: suspend (AuthSession, JsonObject) -> Unit,
    private val onReported: (AuthSession) -> Unit = {},
    private val deliveryStore: AttributionDeliveryStore = MemoryAttributionDeliveryStore(),
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private var session: AuthSession? = null
    private var snapshot: JsonObject? = null
    private val reportedKeys = mutableSetOf<String>()
    private val deferredKeys = mutableSetOf<String>()
    private var retryAfterCurrentRun = false
    private val mutableReceipts = MutableStateFlow<ReportReceipt?>(null)
    val receipts = mutableReceipts.asStateFlow()
    private val mutableExhausted = MutableStateFlow<Set<String>>(emptySet())
    val exhausted = mutableExhausted.asStateFlow()

    init {
        require(attempts > 0)
    }

    fun updateSession(value: AuthSession) {
        val waitingForSnapshot = synchronized(stateLock) {
            session = value
            snapshot == null
        }
        if (waitingForSnapshot) {
            attributionLogD("归因上报已有登录态，等待归因快照，user=${value.userId}")
        }
        kick()
    }

    fun updateSnapshot(value: JsonObject) {
        val waitingForSession = synchronized(stateLock) {
            snapshot = value
            session == null
        }
        if (waitingForSession) {
            attributionLogD(
                "归因快照已就绪，等待登录态，${value.reportSummary(userId = null, digest = value.canonicalString().sha256())}",
            )
        }
        kick()
    }

    fun retryPending() {
        synchronized(stateLock) {
            deferredKeys.clear()
            mutableExhausted.value = emptySet()
            if (running.get()) retryAfterCurrentRun = true
        }
        kick()
    }

    private fun kick() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                drain()
            } finally {
                running.set(false)
                synchronized(stateLock) {
                    if (retryAfterCurrentRun) {
                        retryAfterCurrentRun = false
                        deferredKeys.clear()
                    }
                }
                if (nextCandidate() != null) kick()
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val candidate = nextCandidate() ?: return
            var sent = false
            for (attempt in 0 until attempts) {
                try {
                    attributionLogD(
                        "开始上报归因，attempt=${attempt + 1}/$attempts，${candidate.snapshot.reportSummary(candidate.session.userId, candidate.digest)}",
                    )
                    report(candidate.session, candidate.snapshot)
                    attributionLogI(
                        "归因上报成功，${candidate.snapshot.reportSummary(candidate.session.userId, candidate.digest)}",
                    )
                    sent = true
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    attributionLogW(
                        "归因上报失败，attempt=${attempt + 1}/$attempts，${candidate.snapshot.reportSummary(candidate.session.userId, candidate.digest)}",
                        error,
                    )
                    if (attempt + 1 < attempts) retryDelay()
                }
            }
            if (!sent) {
                synchronized(stateLock) {
                    deferredKeys += candidate.key
                    mutableExhausted.value = mutableExhausted.value + candidate.key
                }
                attributionLogW(
                    "归因上报暂缓，等待下次前台重试，${candidate.snapshot.reportSummary(candidate.session.userId, candidate.digest)}",
                )
                return
            }
            val isCurrent = synchronized(stateLock) {
                reportedKeys += candidate.key
                val matches = session?.epoch == candidate.session.epoch && snapshot == candidate.snapshot
                if (matches) mutableReceipts.value = ReportReceipt(candidate.session.epoch, candidate.snapshot, nowNanos())
                matches
            }
            deliveryStore.markReported(candidate.digest)
            if (isCurrent) {
                onReported(candidate.session)
            }
        }
    }

    private fun nextCandidate(): Candidate? = synchronized(stateLock) {
        val activeSession = session ?: return@synchronized null
        val data = snapshot ?: return@synchronized null
        val key = targetKey(activeSession.epoch, data)
        val digest = key.sha256()
        if (key in reportedKeys || key in deferredKeys) null
        else Candidate(key, digest, activeSession, data)
    }
    fun isExhausted(epoch: String, data: JsonObject): Boolean = targetKey(epoch, data) in exhausted.value

    private fun targetKey(epoch: String, data: JsonObject) = "$epoch:${data.canonicalString()}"

    private data class Candidate(
        val key: String,
        val digest: String,
        val session: AuthSession,
        val snapshot: JsonObject,
    )
}

private fun JsonObject.reportSummary(userId: String?, digest: String): String {
    val digestPrefix = digest.take(12)
    val afUid = stringValue("af_uid")?.takeLast(8)?.let { "***$it" } ?: "-"
    val status = stringValue("af_status") ?: "-"
    val mediaSource = stringValue("media_source") ?: stringValue("network") ?: "-"
    val campaign = stringValue("campaign") ?: "-"
    return "user=${userId ?: "-"} digest=$digestPrefix afUid=$afUid status=$status source=$mediaSource campaign=$campaign"
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.let { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }

private fun attributionLogD(message: String) {
    runCatching { Log.d(ATTRIBUTION_REPORT_TAG, message) }
}

private fun attributionLogI(message: String) {
    runCatching { Log.i(ATTRIBUTION_REPORT_TAG, message) }
}

private fun attributionLogW(message: String, error: Throwable? = null) {
    runCatching {
        if (error == null) Log.w(ATTRIBUTION_REPORT_TAG, message)
        else Log.w(ATTRIBUTION_REPORT_TAG, message, error)
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun JsonElement.canonicalString(): String = when (this) {
    is JsonObject -> entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${kotlinx.serialization.json.Json.encodeToString(key)}:${value.canonicalString()}"
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalString() }
    else -> toString()
}
