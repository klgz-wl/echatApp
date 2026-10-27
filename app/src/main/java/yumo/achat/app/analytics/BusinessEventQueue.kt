package yumo.achat.app.analytics

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.analytics.businessRevenueParameters
import java.util.UUID
import java.security.MessageDigest

internal interface EventDedupeStore {
    fun read(): List<String>
    fun write(values: List<String>)
    fun writeDurably(values: List<String>): Boolean { write(values); return true }
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
    private val persistenceLock = Any()
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
                parameters = businessRevenueParameters(name, parameters) + mapOf(
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

    /** A disabled purchase is still terminal for this account and order. */
    fun suppress(userId: String?, onceKey: String?) {
        try {
            val key = onceKey?.let { onceKey(userId, it) } ?: return
            synchronized(persistenceLock) {
                val snapshot = synchronized(this) { addOnceKey(key); onceKeys.toList() }
                persist(snapshot)
            }
        } catch (_: Exception) { /* 统计存储不能改变支付结果。 */ }
    }

    fun confirm(event: BusinessEvent, accepted: Boolean) {
        val key = event.dedupeKey ?: return
        synchronized(persistenceLock) {
            val snapshot = synchronized(this) {
                inFlightKeys -= key
                if (accepted && addOnceKey(key)) onceKeys.toList() else null
            }
            if (snapshot != null) persist(snapshot)
        }
    }

    private fun onceKey(userId: String?, onceKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${userId.orEmpty()}:$onceKey".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun persist(snapshot: List<String>) {
        repeat(3) { if (runCatching { dedupeStore.writeDurably(snapshot) }.getOrDefault(false)) return }
    }

    private fun addOnceKey(key: String): Boolean {
        if (!onceKeys.add(key)) return false
        if (onceKeys.size > dedupeLimit) {
            val iterator = onceKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }
}
