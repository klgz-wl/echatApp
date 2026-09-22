package yumo.achat.core.integration.appsflyer

internal data class PendingAnalyticsEvent(
    val name: String,
    val parameters: Map<String, Any>,
    val identityWasSet: Boolean,
    val identity: String?,
)

internal data class PendingAnalyticsSnapshot(
    val identityWasSet: Boolean,
    val identity: String?,
    val events: List<PendingAnalyticsEvent>,
)

internal class PendingAnalyticsBuffer(private val capacity: Int = 64) {
    private var identityWasSet = false
    private var identity: String? = null
    private val events = ArrayDeque<PendingAnalyticsEvent>()

    init {
        require(capacity > 0) { "Analytics buffer capacity must be positive" }
    }

    @Synchronized
    fun identify(userId: String?) {
        identityWasSet = true
        identity = userId
    }

    @Synchronized
    fun event(name: String, parameters: Map<String, Any>) {
        if (events.size == capacity) events.removeFirst()
        events.addLast(
            PendingAnalyticsEvent(
                name = name,
                parameters = parameters.toMap(),
                identityWasSet = identityWasSet,
                identity = identity,
            ),
        )
    }

    @Synchronized
    fun drain(): PendingAnalyticsSnapshot = PendingAnalyticsSnapshot(
        identityWasSet = identityWasSet,
        identity = identity,
        events = events.toList(),
    ).also {
        identityWasSet = false
        identity = null
        events.clear()
    }
}
