package yumo.achat.app.analytics

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import yumo.achat.core.analytics.EventTracker
import java.util.UUID

internal data class BusinessEvent(
    val name: String,
    val parameters: Map<String, Any>,
    val userId: String?,
    val eventTime: Long,
)

internal class BusinessEventQueue(
    capacity: Int,
) : EventTracker {
    private val mutableEvents = MutableSharedFlow<BusinessEvent>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    val events = mutableEvents.asSharedFlow()
    private val onceKeys = LinkedHashSet<String>()

    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        if (name.isBlank()) return
        if (onceKey != null && !rememberOnceKey(userId, onceKey)) return
        val eventTime = System.currentTimeMillis()
        mutableEvents.tryEmit(
            BusinessEvent(
                name = name,
                parameters = parameters + mapOf(
                    "event_id" to UUID.randomUUID().toString(),
                    "event_time" to eventTime,
                ),
                userId = userId,
                eventTime = eventTime,
            ),
        )
    }

    @Synchronized
    private fun rememberOnceKey(userId: String?, onceKey: String): Boolean {
        val key = "${userId.orEmpty()}:$onceKey"
        if (!onceKeys.add(key)) return false
        if (onceKeys.size > DEDUPE_LIMIT) {
            val iterator = onceKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    private companion object {
        const val DEDUPE_LIMIT = 10_000
    }
}
