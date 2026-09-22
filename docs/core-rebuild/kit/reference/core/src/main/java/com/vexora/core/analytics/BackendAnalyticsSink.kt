package com.vexora.core.analytics

import com.vexora.core.auth.SessionStorage
import com.vexora.core.config.ClientIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 有界内存队列隔离网络延迟；不持久化、不自动重试，不承诺必达。 */
@Singleton
class BackendAnalyticsSink internal constructor(
    private val api: EventApi,
    private val identity: ClientIdentity,
    private val deviceId: suspend () -> String,
    capacity: Int,
    private val scope: CoroutineScope,
) : AnalyticsSink {
    @Inject constructor(api: EventApi, identity: ClientIdentity, storage: SessionStorage, config: AnalyticsConfiguration) :
        this(api, identity, { storage.deviceId() }, config.queueCapacity, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private data class PendingEvent(val name: String, val time: Long, val userId: String?, val parameters: Map<String, String>)
    private val events = Channel<PendingEvent>(capacity)
    private val initialized = AtomicBoolean(false)
    @Volatile private var userId: String? = null

    override fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            for (event in events) {
                try {
                    val request = ReportEventRequest(
                        deviceId = deviceId(), eventType = AnalyticsPlatform.BACKEND.eventName(event.name), eventTime = event.time, userId = event.userId,
                        packageName = identity.packageName, appVersion = identity.versionName, platform = "android",
                        parameters = event.parameters,
                    )
                    val response = api.reportEvent(request)
                    if (!response.isSuccessful) {
                        response.errorBody()?.close()
                        Timber.tag("BackendAnalytics").w("事件上报失败，HTTP=%d", response.code())
                    } else if (response.body()?.code != 0) {
                        Timber.tag("BackendAnalytics").w("事件上报业务响应失败")
                    } else {
                        Timber.tag("BackendAnalytics").d("事件上报成功：%s", event.name)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 不打印请求、身份或服务端原文；单个失败不能终止后续事件。
                    Timber.tag("BackendAnalytics").w("事件上报异常：%s", error.javaClass.simpleName)
                }
            }
        }
    }

    override fun identify(userId: String?) { this.userId = userId }

    override fun event(name: String, parameters: Map<String, Any>) {
        // 在入队时冻结账号、触发时间和参数，异步执行时不读取新的登录身份。
        val event = PendingEvent(name, (parameters["event_time"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            userId, analyticsParameters(parameters).mapValues { it.value.toString() })
        if (events.trySend(event).isFailure) Timber.tag("BackendAnalytics").w("事件队列已满，丢弃本次埋点")
    }
}
