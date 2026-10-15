package yumo.achat.core.attribution

import yumo.achat.core.auth.SessionStorage
import yumo.achat.core.config.ClientIdentity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Process-scoped live attribution. Disk cache only supplies login fields. */
@Singleton
class AttributionCoordinator @Inject constructor(
    private val source: ConversionSource,
    private val storage: AttributionStorage,
    private val sessions: SessionStorage,
    private val identity: ClientIdentity,
    private val config: AttributionConfiguration,
) : LoginAttributionProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataLock = Mutex()
    private val started = AtomicBoolean(false)
    private val deadlines = mutableMapOf<Stage, Long>()
    private var latest: JsonObject? = null
    private val mutableSnapshots = MutableStateFlow<JsonObject?>(null)
    val snapshots = mutableSnapshots.asStateFlow()
    private enum class Stage { PRE_AUTH, POST_AUTH, HOME }

    private val cacheLoaded = scope.launch {
        try {
            val cached = storage.readAttribution()?.takeIf { it.hasValidAttribution() }
            dataLock.withLock { if (latest == null) latest = cached }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Timber.tag("AppsFlyer/Attribution").w("归因缓存读取失败") }
    }
    init {
        scope.launch {
            source.conversion.collect { result ->
                if (result is ConversionResult.Success) accept(result.data)
            }
        }
        scope.launch {
            snapshots.filterNotNull().collect { data ->
                try { storage.writeAttribution(data) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Timber.tag("AppsFlyer/Attribution").w("归因缓存保存失败") }
            }
        }
    }
    private suspend fun accept(data: JsonObject) = dataLock.withLock {
        if (!data.hasValidAttribution()) return@withLock
        if (mutableSnapshots.value?.isMediaAttribution() == true && !data.isMediaAttribution()) return@withLock
        latest = data
        mutableSnapshots.value = data
    }
    private fun ensureGlobalRetry() {
        if (!started.compareAndSet(false, true)) return
        source.startAttribution()
        scope.launch {
            while (true) {
                delay(config.retryIntervalMillis)
                if (snapshots.value?.isMediaAttribution() == true) return@launch
                runCatching { source.retryAttribution() }
            }
        }
    }
    private suspend fun awaitStage(stage: Stage, timeoutMillis: Long): Boolean {
        ensureGlobalRetry()
        (source.conversion.value as? ConversionResult.Success)?.let { accept(it.data) }
        if (snapshots.value?.isMediaAttribution() == true) return true
        val deadline = synchronized(deadlines) {
            deadlines.getOrPut(stage) { System.nanoTime() + timeoutMillis * 1_000_000L }
        }
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return snapshots.value?.isMediaAttribution() == true
        withTimeoutOrNull((remainingNanos + 999_999L) / 1_000_000L) {
            snapshots.first { it?.isMediaAttribution() == true }
        }
        return snapshots.value?.isMediaAttribution() == true
    }
    suspend fun awaitInitial(): Boolean = awaitStage(Stage.PRE_AUTH, config.timeoutMillis)
    suspend fun awaitAfterAuth(): Boolean = awaitStage(Stage.POST_AUTH, config.loginTimeoutMillis)
    suspend fun awaitHome(): Boolean = awaitStage(Stage.HOME, config.homeTimeoutMillis)
    override suspend fun forLogin(): LoginAttribution {
        awaitInitial()
        withTimeoutOrNull(config.cacheWaitMillis) { cacheLoaded.join() }
        (source.conversion.value as? ConversionResult.Success)?.let { accept(it.data) }
        val data = dataLock.withLock { latest } ?: JsonObject(emptyMap())
        return attributionPayload(data, identity, source.advertisingId() ?: sessions.deviceId(), source.attributionUid())
    }
}

fun JsonObject.isMediaAttribution(): Boolean =
    (get("af_status") as? JsonPrimitive)?.contentOrNull?.equals("non-organic", ignoreCase = true) == true
