package yumo.achat.core.integration.appsflyer

internal data class PendingAppsFlyerEvent(
    val name: String,
    val parameters: Map<String, Any>,
    val userId: String?,
)

internal data class PendingAppsFlyerBatch(
    val events: List<PendingAppsFlyerEvent>,
    val restoreUserId: String?,
)

internal class PendingAppsFlyerEvents(private val capacity: Int) {
    private val events = ArrayDeque<PendingAppsFlyerEvent>()
    private var activeUserId: String? = null

    init { require(capacity > 0) }

    @Synchronized
    fun identify(userId: String?) { activeUserId = userId }

    @Synchronized
    fun enqueue(name: String, parameters: Map<String, Any>): Boolean {
        if (events.size >= capacity) return false
        events.addLast(PendingAppsFlyerEvent(name, parameters.toMap(), activeUserId))
        return true
    }

    fun enqueueOrThrow(name: String, parameters: Map<String, Any>) {
        check(enqueue(name, parameters)) { "AppsFlyer pre-start event queue is full" }
    }

    @Synchronized
    fun drain(): PendingAppsFlyerBatch {
        val batch = PendingAppsFlyerBatch(events.toList(), activeUserId)
        events.clear()
        return batch
    }
}
