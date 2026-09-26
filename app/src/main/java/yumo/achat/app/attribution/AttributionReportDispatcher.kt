package yumo.achat.app.attribution

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import yumo.achat.core.backend.AuthSession
import java.security.MessageDigest

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
) {
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private var session: AuthSession? = null
    private var snapshot: JsonObject? = null
    private val reportedKeys = mutableSetOf<String>()
    private val deferredKeys = mutableSetOf<String>()
    private var retryAfterCurrentRun = false

    init {
        require(attempts > 0)
    }

    fun updateSession(value: AuthSession) {
        synchronized(stateLock) { session = value }
        kick()
    }

    fun updateSnapshot(value: JsonObject) {
        synchronized(stateLock) { snapshot = value }
        kick()
    }

    fun retryPending() {
        synchronized(stateLock) {
            deferredKeys.clear()
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
                    report(candidate.session, candidate.snapshot)
                    sent = true
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (attempt + 1 < attempts) retryDelay()
                }
            }
            if (!sent) {
                synchronized(stateLock) { deferredKeys += candidate.key }
                return
            }
            synchronized(stateLock) { reportedKeys += candidate.key }
            deliveryStore.markReported(candidate.digest)
            onReported(candidate.session)
        }
    }

    private fun nextCandidate(): Candidate? = synchronized(stateLock) {
        val activeSession = session ?: return@synchronized null
        val data = snapshot ?: return@synchronized null
        val key = "${activeSession.userId}:${data.canonicalString()}"
        val digest = key.sha256()
        if (key in reportedKeys || key in deferredKeys || deliveryStore.wasReported(digest)) null
        else Candidate(key, digest, activeSession, data)
    }

    private data class Candidate(
        val key: String,
        val digest: String,
        val session: AuthSession,
        val snapshot: JsonObject,
    )
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
