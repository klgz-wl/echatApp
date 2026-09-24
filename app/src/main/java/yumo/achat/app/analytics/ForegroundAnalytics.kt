package yumo.achat.app.analytics

import yumo.achat.core.analytics.EventTracker

internal class ForegroundAnalytics(
    private val events: EventTracker,
    private val backgroundTimeoutMs: Long,
    private val nowElapsed: () -> Long,
    private val firstLaunch: () -> Boolean,
    private val newSession: () -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    private var cancelPending: (() -> Unit)? = null
    private var active = false
    private var cold = true
    private var ended = false
    private var segmentStart = 0L
    private var duration = 0L

    @Synchronized
    fun foreground(value: Boolean) {
        if (value == active) return
        active = value
        if (value) {
            cancelPending?.invoke()
            cancelPending = null
            if (ended) {
                newSession()
                duration = 0L
                ended = false
            }
            segmentStart = nowElapsed()
            events.track(
                "app_launch",
                mapOf(
                    "is_first_launch" to firstLaunch(),
                    "launch_type" to if (cold) "cold" else "warm",
                ),
            )
            cold = false
        } else {
            duration += (nowElapsed() - segmentStart).coerceAtLeast(0L)
            cancelPending = schedule(backgroundTimeoutMs) {
                synchronized(this) {
                    if (active || ended) return@synchronized
                    events.track(
                        "app_exit",
                        mapOf(
                            "session_duration" to duration,
                            "exit_reason" to "background_timeout",
                        ),
                    )
                    ended = true
                }
            }
        }
    }
}
