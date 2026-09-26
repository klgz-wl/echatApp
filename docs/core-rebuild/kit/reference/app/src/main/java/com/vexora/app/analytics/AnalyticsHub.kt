package com.vexora.app.analytics

import com.zorv.core.analytics.*
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.auth.SessionStorage
import com.zorv.core.config.ClientIdentity
import com.zorv.core.integration.appsflyer.AppsFlyerAnalytics
import com.zorv.core.integration.thinkingdata.ThinkingDataAnalytics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsHub @Inject constructor(private val firebase: FirebaseAnalyticsSink, private val appsFlyer: AppsFlyerAnalytics,
    private val thinkingData: ThinkingDataAnalytics, private val backend: BackendAnalyticsSink,
    private val sessions: SessionCoordinator, private val policy: AnalyticsPolicy,
    private val queue: BusinessEventQueue, private val storage: SessionStorage, private val identity: ClientIdentity,
    private val attributionReports: dagger.Lazy<com.zorv.core.attribution.AttributionReports>) {
    private val sinks = listOf(firebase to policy.firebase, appsFlyer to policy.appsFlyer, thinkingData to policy.thinkingData, backend to policy.backend)
        .filter { it.second }.map { it.first }
    private val identities = mutableMapOf<AnalyticsSink, String?>()
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        sinks.forEach { sink -> safely { sink.initialize() } }
        attributionReports.get().start()
        scope.launch { sessions.state.map { it?.userId }.distinctUntilChanged().collect { user ->
            sinks.forEach { sink -> safely { identify(sink, user) } }
        } }
        scope.launch {
            val deviceId = try { storage.deviceId() } catch (_: Exception) { "" }
            queue.events.collect { event ->
                // 已切换账号的延迟业务事件不挂到当前 SDK 用户下。
                if (event.userId != null && sessions.current?.userId != event.userId) return@collect
                val values = analyticsParameters(event.parameters + mapOf<String, Any>("device_id" to deviceId, "user_id" to event.userId.orEmpty(),
                    "app_version" to identity.versionName, "platform" to "android"))
                Timber.tag("Analytics").d("事件=%s，字段=%s", event.name, values.keys.joinToString(","))
                sinks.forEach { sink -> safely {
                    try {
                    identify(sink, event.userId)
                    // 四端共用官方参数名；收入已在 Core 限定为主支付成功事件。
                    sink.event(event.name, values)
                    } finally { identify(sink, sessions.current?.userId) }
                } }
            }
        }
    }
    private fun identify(sink: AnalyticsSink, userId: String?) {
        if (identities.containsKey(sink) && identities[sink] == userId) return
        sink.identify(userId)
        identities[sink] = userId
    }
    private inline fun safely(block: () -> Unit) {
        try { block() } catch (error: Exception) { Timber.w("统计操作失败：%s", error.javaClass.simpleName) }
    }
}
