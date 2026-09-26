package com.vexora.app.analytics

import android.os.SystemClock
import com.zorv.core.analytics.AnalyticsConfiguration
import kotlinx.coroutines.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 只由正式 MainActivity 驱动；配置重建不算新启动，后台超时结束会话。 */
@Singleton
class ForegroundAnalytics @Inject constructor(private val events: BusinessEventQueue, private val config: AnalyticsConfiguration) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var active = false
    private var cold = true
    private var segmentStart = 0L
    private var duration = 0L
    private var ended = false
    fun foreground(value: Boolean) {
        if (value == active) return
        active = value
        if (value) {
            pending?.cancel()
            if (ended) { events.sessionId = UUID.randomUUID().toString(); duration = 0; ended = false }
            segmentStart = SystemClock.elapsedRealtime()
            events.track("app_launch", mapOf("is_first_launch" to events.firstLaunch(), "launch_type" to if (cold) "cold" else "warm"))
            cold = false
        } else {
            duration += (SystemClock.elapsedRealtime() - segmentStart).coerceAtLeast(0)
            pending = scope.launch {
                delay(config.backgroundTimeoutMs)
                events.track("app_exit", mapOf("session_duration" to duration, "exit_reason" to "background_timeout"))
                ended = true
            }
        }
    }
}
