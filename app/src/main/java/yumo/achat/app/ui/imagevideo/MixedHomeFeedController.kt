package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import yumo.achat.core.backend.HomeFeedBatch
import yumo.achat.core.backend.HomeFeedGateway
import yumo.achat.core.backend.HomeFeedItem
import yumo.achat.core.backend.HomeFeedServiceException

data class HomeFeedEntry(val item: HomeFeedItem, val batchId: String, val absoluteIndex: Int) {
    val key: String get() = "$batchId:$absoluteIndex"
}

/** Call UI methods from the same dispatcher as [scope]. Only one request is active at a time. */
class MixedHomeFeedController(
    private val gateway: HomeFeedGateway,
    private val scope: CoroutineScope,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val isOwner: suspend () -> Boolean = { true },
) {
    var entries by mutableStateOf<List<HomeFeedEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    var exhausted by mutableStateOf(false)
        private set
    val empty: Boolean get() = exhausted && entries.isEmpty()

    private data class Ack(val batchId: String, val index: Int, val key: String)
    private var requestId = idFactory()
    private var batch: HomeFeedBatch? = null
    private var acknowledged = -1
    private val shown = mutableSetOf<String>()
    private var pendingAck: Ack? = null
    private var job: Job? = null
    private var generation = 0
    private var closed = false
    private var recoveryUsed = false

    fun start() {
        if (!error) pump()
    }

    fun displayed(entryKey: String) {
        if (closed || entries.none { it.key == entryKey }) return
        shown += entryKey
        if (!error) pump()
    }

    fun retry() {
        if (closed) return
        error = false
        recoveryUsed = false
        pump()
    }

    fun refresh() {
        if (closed) return
        generation++
        job?.cancel()
        job = null
        reset()
        recoveryUsed = false
        pump()
    }

    fun close() {
        closed = true
        generation++
        job?.cancel()
        job = null
        entries = emptyList()
        shown.clear()
        pendingAck = null
        batch = null
        loading = false
    }

    private fun reset() {
        entries = emptyList()
        shown.clear()
        batch = null
        pendingAck = null
        acknowledged = -1
        requestId = idFactory()
        error = false
        exhausted = false
    }

    private suspend fun owns(token: Int): Boolean {
        if (closed || token != generation) return false
        val valid = isOwner()
        if (closed || token != generation) return false
        if (!valid) close()
        return valid
    }

    private fun pump() {
        if (closed || exhausted || error || job?.isActive == true) return
        val token = generation
        job = scope.launch {
            loading = true
            try {
                while (owns(token)) {
                    try {
                        val current = batch
                        if (current == null) {
                            val result = HomeLoadDiagnostics.timed("feed") {gateway.homeFeed(requestId)}
                            if (!owns(token)) return@launch
                            if (result.items.isEmpty()) {
                                exhausted = true
                                return@launch
                            }
                            batch = result
                            acknowledged = result.offset - 1
                            val existing = entries.mapTo(mutableSetOf()) { it.key }
                            entries = entries + result.items.mapIndexed { index, item ->
                                HomeFeedEntry(item, result.batchId, result.offset + index)
                            }.filter { existing.add(it.key) }
                            continue
                        }
                        if (pendingAck == null) {
                            var through = acknowledged
                            val last = current.offset + current.items.lastIndex
                            while (through < last && "${current.batchId}:${through + 1}" in shown) through++
                            if (through == acknowledged) return@launch
                            pendingAck = Ack(current.batchId, through, idFactory())
                        }
                        val ack = checkNotNull(pendingAck)
                        gateway.acknowledgeHomeFeed(ack.batchId, ack.index, ack.key)
                        if (!owns(token)) return@launch
                        acknowledged = ack.index
                        pendingAck = null
                        if (acknowledged == current.offset + current.items.lastIndex) {
                            batch = null
                            requestId = idFactory()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!owns(token)) return@launch
                        val invalidated = failure is HomeFeedServiceException &&
                            failure.statusCode == 409 &&
                            failure.code in setOf("BATCH_INVALIDATED", "FEED_FILTER_CONFLICT")
                        if (invalidated) {
                            reset()
                            if (!recoveryUsed) {
                                recoveryUsed = true
                                continue
                            }
                        }
                        error = true
                        return@launch
                    }
                }
            } finally {
                if (token == generation) loading = false
            }
        }
    }
}
