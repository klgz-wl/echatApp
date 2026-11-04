package yumo.achat.core.attribution

/** Tickets are captured before dispatch; a new owner cannot authorize an old queued write. */
class AttributionAcquisitionFence {
    private val lock = Any()
    private var generation = 0L
    private var active = true

    fun ticket(): Long? = synchronized(lock) { generation.takeIf { active } }
    fun permits(ticket: Long): Boolean = synchronized(lock) { active && generation == ticket }
    fun beginSubmission() = synchronized(lock) { generation++; active = true }
    fun pause() = synchronized(lock) { generation++; active = false }
    fun suppress() = pause()
    fun activate() = synchronized(lock) { if (!active) { generation++; active = true } }
}
