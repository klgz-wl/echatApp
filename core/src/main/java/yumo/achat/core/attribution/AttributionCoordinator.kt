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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
    private val advertisingIds: AdvertisingIdProvider = AdvertisingIdProvider { null },
) : LoginAttributionProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataLock = Mutex()
    private val started = AtomicBoolean(false)
    private val deadlines = mutableMapOf<Stage, Long>()
    private var latest: JsonObject? = null
    private val accepted = mutableSetOf<Pair<AttributionResultSource, JsonObject>>()
    private val mutableSnapshots = MutableStateFlow<JsonObject?>(null)
    val snapshots = mutableSnapshots.asStateFlow()
    private val mutableResults = MutableSharedFlow<AttributionResult>(replay = 16, extraBufferCapacity = 16)
    val results = mutableResults.asSharedFlow()
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
            source.results.collect { result ->
                accept(result.data, result.source)
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
    private suspend fun accept(
        data: JsonObject,
        source: AttributionResultSource = AttributionResultSource.APPSFLYER_SDK,
    ) = dataLock.withLock {
        if (!data.hasValidAttribution()) return@withLock
        val sourced = source to data
        if (accepted.add(sourced)) mutableResults.tryEmit(AttributionResult(source, data))
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
                if (snapshots.value?.hasValidAttribution() == true ||
                    (source.conversion.value as? ConversionResult.Success)?.data?.hasValidAttribution() == true) return@launch
                runCatching { source.retryAttribution() }
            }
        }
    }
    private suspend fun awaitStage(stage: Stage, timeoutMillis: Long): Boolean {
        ensureGlobalRetry()
        (source.conversion.value as? ConversionResult.Success)?.let { accept(it.data, it.source) }
        if (snapshots.value?.isMediaAttribution() == true) return true
        val deadline = synchronized(deadlines) {
            deadlines.getOrPut(stage) { System.nanoTime() + timeoutMillis * 1_000_000L }
        }
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) return snapshots.value?.isMediaAttribution() == true
        withTimeoutOrNull((remainingNanos + 999_999L) / 1_000_000L) {
            val confirmation = source.preHomeConfirmation
            if (confirmation == null) {
                snapshots.first { it?.isMediaAttribution() == true }
            } else {
                combine(snapshots, confirmation) { data, confirmed -> data to confirmed }
                    .first { (data, confirmed) ->
                        data?.isMediaAttribution() == true ||
                            (data?.hasValidAttribution() == true && confirmed)
                    }
            }
        }
        return snapshots.value?.isMediaAttribution() == true
    }
    suspend fun awaitInitial(): Boolean = awaitStage(Stage.PRE_AUTH, config.timeoutMillis)
    suspend fun awaitAfterAuth(): Boolean = awaitStage(Stage.POST_AUTH, config.loginTimeoutMillis)
    suspend fun awaitHome(): Boolean {
        ensureGlobalRetry()
        source.verifyHomeAttribution()
        (source.conversion.value as? ConversionResult.Success)?.let { accept(it.data, it.source) }
        return snapshots.value?.isMediaAttribution() == true
    }
    override suspend fun forLogin(): LoginAttribution {
        awaitInitial()
        withTimeoutOrNull(config.cacheWaitMillis) { cacheLoaded.join() }
        (source.conversion.value as? ConversionResult.Success)?.let { accept(it.data, it.source) }
        val data = dataLock.withLock { latest } ?: JsonObject(emptyMap())
        val gaid = advertisingIds.readOrNull()
        return attributionPayload(data, identity, gaid ?: sessions.deviceId(), source.attributionUid(), gaid = gaid)
    }
}

fun JsonObject.isMediaAttribution(): Boolean =
    (get("af_status") as? JsonPrimitive)?.contentOrNull?.equals("non-organic", ignoreCase = true) == true
