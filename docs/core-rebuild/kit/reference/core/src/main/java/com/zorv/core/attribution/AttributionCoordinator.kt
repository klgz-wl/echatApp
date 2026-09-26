package com.zorv.core.attribution

import com.zorv.core.auth.SessionStorage
import com.zorv.core.config.ClientIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 安装归因独立于账号；只协调登录前等待和真实回调，不参与 A/B 展示判断。 */
@Singleton
class AttributionCoordinator @Inject constructor(private val source: ConversionSource,
    private val storage: AttributionStorage, private val sessions: SessionStorage,
    private val identity: ClientIdentity, private val config: AttributionConfiguration) : LoginAttributionProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataLock = Mutex()
    private val resolveLock = Mutex()
    private var latest: JsonObject? = null
    private var resolved: Boolean? = null
    private val mutableSnapshots = MutableStateFlow<JsonObject?>(null)
    val snapshots = mutableSnapshots.asStateFlow()
    private val cacheLoaded = scope.launch {
            try {
                val cached = storage.readAttribution()?.takeIf { it.hasValidAttribution() }
                dataLock.withLock { if (latest == null) latest = cached }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Timber.tag("AppsFlyer/Attribution").w("归因缓存读取失败，等待 SDK 回调") }
    }
    init {
        scope.launch { source.conversion.collect { if (it is ConversionResult.Success) accept(it.data) } }
        scope.launch {
            snapshots.filterNotNull().collectLatest { data ->
                try { storage.writeAttribution(data) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Timber.tag("AppsFlyer/Attribution").w("归因缓存保存失败，本次仍使用真实回调") }
            }
        }
    }
    private suspend fun accept(data: JsonObject) = dataLock.withLock {
        if (!data.hasValidAttribution()) return@withLock
        latest = data
        mutableSnapshots.value = data
    }
    suspend fun awaitInitial(): Boolean = resolveLock.withLock {
        resolved?.let { return@withLock it }
        source.startAttribution()
        val result = withTimeoutOrNull(config.timeoutMillis) {
            source.conversion.first { it !is ConversionResult.Pending }
        }
        if (result is ConversionResult.Success) accept(result.data)
        // 失败或超时只放行登录；展示模式由登录后的 profile 决定。
        val succeeded = (result as? ConversionResult.Success)?.data?.hasValidAttribution() == true
        resolved = succeeded
        if (!succeeded) scope.launch {
            repeat(config.retryAttempts) {
                delay(config.retryIntervalMillis)
                if ((source.conversion.value as? ConversionResult.Success)?.data?.hasValidAttribution() == true) return@launch
                source.retryAttribution()
                val next = withTimeoutOrNull(config.timeoutMillis) {
                    source.conversion.first { it !is ConversionResult.Pending }
                }
                if (next is ConversionResult.Success && next.data.hasValidAttribution()) {
                    accept(next.data)
                    return@launch
                }
            }
        }
        Timber.tag("AppsFlyer/Attribution").d("归因等待完成，来源=%s", if (succeeded) "SDK回调" else "无结果放行登录")
        succeeded
    }
    override suspend fun forLogin(): LoginAttribution {
        awaitInitial()
        // 等待正常缓存读取消除竞态；磁盘异常不得无限阻塞匿名登录。
        withTimeoutOrNull(config.cacheWaitMillis) { cacheLoaded.join() }
        val current = source.conversion.value
        if (current is ConversionResult.Success) accept(current.data)
        val data = dataLock.withLock { latest } ?: JsonObject(emptyMap())
        return attributionPayload(data, identity, source.advertisingId() ?: sessions.deviceId(), source.attributionUid())
    }
}
