package com.vexora.app.analytics

import android.content.Context
import com.zorv.core.analytics.*
import com.zorv.core.auth.SessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BusinessEvent(val name: String, val parameters: Map<String, Any>, val userId: String?)

/** SDK 初始化前可缓存少量事件。统计失败不影响交易；本地去重不代表服务端 exactly-once。 */
@Singleton
class BusinessEventQueue @Inject constructor(@ApplicationContext context: Context,
    private val sessions: SessionCoordinator, private val config: AnalyticsConfiguration) : EventTracker {
    private val preferences = context.getSharedPreferences(config.storageName, Context.MODE_PRIVATE)
    private val channel = Channel<BusinessEvent>(config.queueCapacity)
    val events = channel.receiveAsFlow()
    @Volatile var mode: String = "unknown"
    @Volatile var sessionId: String = UUID.randomUUID().toString()
    @Synchronized override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        runCatching {
            val owner = userId ?: sessions.current?.userId
            val key = onceKey?.let { java.security.MessageDigest.getInstance("SHA-256")
                .digest("$owner:$it".toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            val seen = (if (key == null) "" else preferences.getString("dedupe", "")).orEmpty().split('\n').filter { it.isNotEmpty() }
            if (key != null && key in seen) return
            val common = mapOf<String, Any>("event_id" to UUID.randomUUID().toString(), "event_time" to System.currentTimeMillis(),
                "session_id" to sessionId, "app_mode" to mode)
            if (channel.trySend(BusinessEvent(name, parameters + common, owner)).isSuccess && key != null)
                preferences.edit().putString("dedupe", (seen + key).takeLast(config.dedupeLimit).joinToString("\n")).apply()
        }
    }
    fun firstLaunch(): Boolean = runCatching {
        !preferences.getBoolean("launched", false).also { preferences.edit().putBoolean("launched", true).apply() }
    }.getOrDefault(false)
}
