package yumo.achat.core.analytics

class RecordingTracker : EventTracker {
    data class Event(val name: String, val values: Map<String, Any>, val user: String?, val once: String?)
    val events = mutableListOf<Event>()
    private val keys = mutableSetOf<String>()
    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        if (onceKey == null || keys.add("$userId:$onceKey")) events += Event(name, parameters, userId, onceKey)
    }
}
