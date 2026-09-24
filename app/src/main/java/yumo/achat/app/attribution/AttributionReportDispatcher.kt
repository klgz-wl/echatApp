package yumo.achat.app.attribution

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import yumo.achat.core.backend.AuthSession

internal class AttributionReportDispatcher(
    private val attempts: Int,
    private val scope: CoroutineScope,
    private val retryDelay: suspend () -> Unit,
    private val report: suspend (AuthSession, JsonObject) -> Unit,
    private val onReported: (AuthSession) -> Unit = {},
) {
    private val stateLock = Any()
    private val running = AtomicBoolean(false)
    private var session: AuthSession? = null
    private var snapshot: JsonObject? = null
    private val reportedKeys = mutableSetOf<String>()
    private val exhaustedKeys = mutableSetOf<String>()

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

    private fun kick() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                drain()
            } finally {
                running.set(false)
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
                synchronized(stateLock) { exhaustedKeys += candidate.key }
                return
            }
            synchronized(stateLock) { reportedKeys += candidate.key }
            onReported(candidate.session)
        }
    }

    private fun nextCandidate(): Candidate? = synchronized(stateLock) {
        val activeSession = session ?: return@synchronized null
        val data = snapshot ?: return@synchronized null
        val key = "${activeSession.userId}:$data"
        if (key in reportedKeys || key in exhaustedKeys) null else Candidate(key, activeSession, data)
    }

    private data class Candidate(
        val key: String,
        val session: AuthSession,
        val snapshot: JsonObject,
    )
}
