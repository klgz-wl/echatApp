package yumo.achat.app.analytics

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import yumo.achat.core.analytics.EventTracker
import java.util.UUID
import java.security.MessageDigest

internal interface EventDedupeStore {
    fun read(): List<String>
    fun write(values: List<String>)
}

private class MemoryEventDedupeStore : EventDedupeStore {
    private var values = emptyList<String>()
    override fun read(): List<String> = values
    override fun write(values: List<String>) { this.values = values }
}

internal data class BusinessEvent(
    val name: String,
    val parameters: Map<String, Any>,
    val userId: String?,
    val eventTime: Long,
    val dedupeKey: String? = null,
)

internal class BusinessEventQueue(
    capacity: Int,
    private val dedupeLimit: Int = 10_000,
    private val dedupeStore: EventDedupeStore = MemoryEventDedupeStore(),
) : EventTracker {
    private val channel = Channel<BusinessEvent>(capacity)
    val events = channel.receiveAsFlow()
    private val onceKeys = LinkedHashSet(dedupeStore.read().takeLast(dedupeLimit))
    private val inFlightKeys = mutableSetOf<String>()
    @Volatile var mode: String = "unknown"
    @Volatile var sessionId: String = UUID.randomUUID().toString()

    @Synchronized
    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        if (name.isBlank()) return
        val dedupeKey = onceKey?.let { onceKey(userId, it) }
        if (dedupeKey != null && (dedupeKey in onceKeys || dedupeKey in inFlightKeys)) return
        val eventTime = System.currentTimeMillis()
        val sent = channel.trySend(
            BusinessEvent(
                name = name,
                parameters = parameters.toMap() + mapOf(
                    "event_id" to UUID.randomUUID().toString(),
                    "event_time" to eventTime,
                    "session_id" to sessionId,
                    "app_mode" to mode,
                ),
                userId = userId,
                eventTime = eventTime,
                dedupeKey = dedupeKey,
            ),
        ).isSuccess
        if (sent && dedupeKey != null) inFlightKeys += dedupeKey
    }

    @Synchronized
    fun confirm(event: BusinessEvent, accepted: Boolean) {
        val key = event.dedupeKey ?: return
        inFlightKeys -= key
        if (accepted) rememberOnceKey(key)
    }

    private fun onceKey(userId: String?, onceKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${userId.orEmpty()}:$onceKey".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun rememberOnceKey(key: String) {
        if (!onceKeys.add(key)) return
        if (onceKeys.size > dedupeLimit) {
            val iterator = onceKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        dedupeStore.write(onceKeys.toList())
    }
}
