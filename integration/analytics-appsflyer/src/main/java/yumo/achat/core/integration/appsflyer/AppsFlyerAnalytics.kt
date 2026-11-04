package yumo.achat.core.integration.appsflyer

import android.app.Activity
import android.content.Context
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.AppsFlyerConversionListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import timber.log.Timber
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.analytics.AttributionIdProvider
import yumo.achat.core.attribution.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal fun appsFlyerCollectionAllowed(enabled: Boolean, canCollect: Boolean): Boolean =
    enabled && canCollect

@Singleton
class AppsFlyerAnalytics @Inject constructor(@ApplicationContext private val context: Context,
    private val config: AppsFlyerConfig) : AnalyticsSink, AttributionIdProvider, ConversionSource {
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var sdkMediaObserved = config.restoredAttribution?.data?.isMediaAttribution() == true
    @Volatile private var acquisitionSuppressed = config.restoredAttribution != null
    @Volatile private var attributionListening = false
    @Volatile private var acquisitionPaused = false
    private var listenerRegistered = false
    @Volatile private var acquisitionGeneration = 0L
    private val sdkLock = Any()
    private val pendingEvents = PendingAppsFlyerEvents(config.eventQueueCapacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStartStatus = MutableStateFlow(AttributionStartStatus(AttributionStartStage.NOT_REQUESTED))
    val startStatus = mutableStartStatus.asStateFlow()
    private val conversionState = AppsFlyerConversionState()
    override val conversion = conversionState.conversion
    override val results = conversionState.results
    private val foregroundStart = ForegroundAttributionStart<Activity>(
        valid = { !it.isFinishing && !it.isDestroyed }, start = ::startWithActivity,
        timeoutMillis = config.requestTimeoutMillis,
        minIntervalMillis = config.minSessionIntervalSeconds * 1_000L)
    private val gcdClient = GcdAttributionClient(config.gcd, context.packageName, config.devKey)
    private val installReferrer = InstallReferrerAttributionClient(
        context, config.installReferrerStorageName, config.installReferrerPaidMediums,
    )
    private val gcd = GcdRecheckCoordinator<Activity>(
        scope = scope,
        timeoutMillis = config.gcd.timeoutMillis,
        uid = ::currentId,
        eligible = { ready && config.enabled && config.canCollect() && !sdkMediaObserved && !acquisitionSuppressed && !acquisitionPaused },
        validHost = { !it.isFinishing && !it.isDestroyed },
        fetch = gcdClient::fetch,
        referrer = { afUid -> withTimeoutOrNull(config.installReferrerTimeoutMillis) { installReferrer.supplement(afUid) } },
        onResult = conversionState::auxiliarySnapshot,
        persistResult = { source, data -> config.onAttributionResult(AttributionResult(source, data)) },
    )
    private val callbackSequencer = AppsFlyerConversionCallbackSequencer()
    private val callbackEvents = ConversionCallbackEvents<AppsFlyerConversionCallback>()
    val conversionCallbacks = callbackEvents.events
    init {
        config.restoredAttribution?.let(conversionState::restoreSnapshot)
        if (sdkMediaObserved) gcd.sdkMedia()
    }
    /** 仅在地区检查通过后，由 MainActivity 的 RESUMED 生命周期绑定。 */
    fun onActivityResumed(activity: Activity) {
        foregroundStart.attach(activity)
        gcd.attach(activity)
        if (acquisitionSuppressed && !acquisitionPaused && !started) requestStart(retry = false, attribution = false)
    }
    fun onActivityPaused(activity: Activity) { foregroundStart.detach(activity); gcd.detach(activity) }
    fun onAccessRevoked() { foregroundStart.revokeAccess(); gcd.revokeAccess() }

    override fun initialize() { requestStart(retry = false, attribution = false) }
    override fun startAttribution() { requestStart(retry = false) }
    override fun retryAttribution() { requestStart(retry = true) }
    private fun requestStart(retry: Boolean, attribution: Boolean = true) {
        scope.launch {
            if (acquisitionPaused) return@launch
            if (!appsFlyerCollectionAllowed(config.enabled, config.canCollect())) {
                updateStartStatus(AttributionStartStage.DISABLED)
                conversionState.sdkFailure()
                return@launch
            }
            if (attribution) {
                if (acquisitionSuppressed) return@launch
                attributionListening = true
                synchronized(sdkLock) {
                    if (ready && !listenerRegistered) {
                        AppsFlyerLib.getInstance().registerConversionListener(context, conversionListener())
                        listenerRegistered = true
                    }
                }
            }
            val known = conversionState.data
            if (known?.hasValidAttribution() == true && started) {
                return@launch
            }
            if (mutableStartStatus.value.stage == AttributionStartStage.NOT_REQUESTED)
                updateStartStatus(AttributionStartStage.WAITING_FOR_ACTIVITY)
            foregroundStart.request(retry)
        }
    }

    private fun startWithActivity(activity: Activity, attempt: Long): Boolean {
        try {
            synchronized(sdkLock) { AppsFlyerLib.getInstance().apply {
                if (!ready) {
                    setDebugLog(false)
                    setMinTimeBetweenSessions(config.minSessionIntervalSeconds)
                    init(config.devKey, if (attributionListening && !acquisitionSuppressed) conversionListener() else null, context)
                    listenerRegistered = attributionListening && !acquisitionSuppressed
                    ready = true
                }
                if (attributionListening && !acquisitionSuppressed) conversionState.startPending()
                updateStartStatus(AttributionStartStage.START_CALLED)
                // 延迟启动必须传当前 Activity；Application Context 会遗漏已经发生的首次前台事件。
                start(activity, config.devKey, object : AppsFlyerRequestListener {
                    override fun onSuccess() {
                        scope.launch {
                            if (attempt == foregroundStart.latestAttempt) {
                                foregroundStart.launchAccepted(attempt)
                                updateStartStatus(AttributionStartStage.REQUEST_ACCEPTED)
                            }
                        }
                        // 请求被接收不等于取得安装归因，conversion 只由 ConversionListener 更新。
                    }
                    override fun onError(code: Int, description: String) {
                        scope.launch {
                            if (attempt == foregroundStart.latestAttempt) {
                                started = false
                                foregroundStart.launchFailed(attempt)
                                updateStartStatus(AttributionStartStage.REQUEST_FAILED, code)
                                conversionState.sdkFailure()
                            }
                        }
                    }
                })
                started = true
                val batch = pendingEvents.drain()
                batch.events.forEach { event ->
                    setCustomerUserId(event.userId.orEmpty())
                    logEvent(context, yumo.achat.core.analytics.AnalyticsPlatform.APPS_FLYER.eventName(event.name), event.parameters)
                }
                setCustomerUserId(batch.restoreUserId.orEmpty())
            } }
            return true
        } catch (_: Exception) {
            started = false
            foregroundStart.launchFailed(attempt)
            updateStartStatus(AttributionStartStage.INITIALIZATION_FAILED)
            conversionState.sdkFailure()
            return false
        }
    }
    private fun conversionListener(): AppsFlyerConversionListener {
        val listenerGeneration = acquisitionGeneration
        fun active(): Boolean = attributionListening && !acquisitionSuppressed && !acquisitionPaused &&
            listenerGeneration == acquisitionGeneration
        return object : AppsFlyerConversionListener {
            override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                val owner = config.callbackOwner()
                val callbackAttempt = foregroundStart.latestAttempt
                if (!active()) return
                val snapshot = appsFlyerConversionSnapshot(data.orEmpty(), currentId())
                val status = (snapshot["af_status"] as? JsonPrimitive)?.contentOrNull
                callbackEvents.publish(callbackSequencer.success(status, callbackAttempt, owner))
                scope.launch {
                    if (!active()) return@launch
                    acceptSdkSnapshot(snapshot, ::active)
                }
            }

            override fun onConversionDataFail(message: String?) {
                val owner = config.callbackOwner()
                val callbackAttempt = foregroundStart.latestAttempt
                if (!active()) return
                callbackEvents.publish(callbackSequencer.failure(callbackAttempt, owner))
                scope.launch { if (active()) conversionState.sdkFailure() }
                val status = Regex("^Launch status code: ([0-9]{3})$")
                    .matchEntire(message.orEmpty().trim())?.groupValues?.get(1)
                diagnostic("AppsFlyer/Attribution", "SDK 归因回调失败，HTTP=${status ?: "unknown"}")
            }

            override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
                if (!active()) return
                val snapshot = appsFlyerAppOpenAttributionSnapshot(data.orEmpty(), currentId())
                if (snapshot.hasValidAttribution()) scope.launch {
                    if (active()) acceptSdkSnapshot(snapshot, ::active)
                }
            }

            override fun onAttributionFailure(message: String?) { }
        }
    }

    private suspend fun acceptSdkSnapshot(snapshot: JsonObject, active: () -> Boolean) {
        try {
            if (snapshot.hasValidAttribution()) {
                config.onAttributionResult(AttributionResult(AttributionResultSource.APPSFLYER_SDK, snapshot))
                if (!active()) return
            }
            conversionState.sdkSnapshot(snapshot)
            if (snapshot.isMediaAttribution()) {
                sdkMediaObserved = true
                foregroundStart.dropPendingRetry()
                gcd.sdkMedia()
            } else if ((snapshot["af_status"] as? JsonPrimitive)?.contentOrNull.equals("Organic", ignoreCase = true)) {
                foregroundStart.dropPendingRetry()
                gcd.sdkOrganic()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostic("AppsFlyer/Attribution", "归因持久化失败，保留待处理状态")
        }
    }
    private fun updateStartStatus(stage: AttributionStartStage, code: Int? = null) {
        mutableStartStatus.value = AttributionStartStatus(stage, code)
        diagnostic("AppsFlyer/Startup", "启动阶段=${stage.name}，错误码=$code")
    }
    private fun diagnostic(tag: String, message: String) {
        if (config.diagnosticLogging) android.util.Log.i(tag, message)
        else Timber.tag(tag).i(message)
    }
    // AppsFlyer SDK collects its own Advertising ID. Owned requests use the shared provider.
    override fun advertisingId(): String? = null
    override fun attributionUid(): String = currentId()
    override suspend fun verifyHomeAttribution() = withContext(Dispatchers.Main.immediate) { gcd.verifyHome() }
    override suspend fun awaitPreHomeConfirmation(): Boolean {
        gcd.preHomeComplete.first { it }
        return true
    }
    override val preHomeConfirmation = gcd.preHomeComplete
    override fun pauseAttributionAcquisition() {
        scope.launch {
            acquisitionPaused = true
            attributionListening = false
            acquisitionGeneration++
            synchronized(sdkLock) {
                if (ready) AppsFlyerLib.getInstance().unregisterConversionListener()
                listenerRegistered = false
            }
            foregroundStart.revokeAccess()
            gcd.revokeAccess()
        }
    }
    override suspend fun stopAttributionAcquisition(restored: AttributionResult?) {
        withContext(Dispatchers.Main.immediate) {
            acquisitionSuppressed = true
            attributionListening = false
            acquisitionGeneration++
            synchronized(sdkLock) {
                if (ready) AppsFlyerLib.getInstance().unregisterConversionListener()
                listenerRegistered = false
            }
            sdkMediaObserved = true
            restored?.let(conversionState::restoreSnapshot)
            foregroundStart.dropPendingRetry()
            gcd.sdkMedia()
        }
    }
    override suspend fun resumeAttributionAcquisition() {
        withContext(Dispatchers.Main.immediate) {
            if (!acquisitionSuppressed && !acquisitionPaused) return@withContext
            val resetIdentity = acquisitionSuppressed
            acquisitionPaused = false
            acquisitionSuppressed = false
            acquisitionGeneration++
            if (resetIdentity) {
                sdkMediaObserved = false
                conversionState.clearRestoredSnapshot()
                gcd.resetForNewIdentity()
            }
            requestStart(retry = true)
        }
    }
    override fun identify(userId: String?) {
        pendingEvents.identify(userId)
        synchronized(sdkLock) {
            if (ready) AppsFlyerLib.getInstance().setCustomerUserId(userId.orEmpty())
        }
    }
    override fun event(name: String, parameters: Map<String, Any>) {
        synchronized(sdkLock) {
            if (started) {
                AppsFlyerLib.getInstance().logEvent(
                    context,
                    yumo.achat.core.analytics.AnalyticsPlatform.APPS_FLYER.eventName(name),
                    parameters,
                )
            } else {
                runCatching { pendingEvents.enqueueOrThrow(name, parameters) }
                    .onFailure { diagnostic("AppsFlyer/Event", "启动前事件队列已满，拒绝最新事件") }
                    .getOrThrow()
            }
        }
    }
    override fun currentId(): String = if (ready) AppsFlyerLib.getInstance().getAppsFlyerUID(context).orEmpty() else ""
}

internal fun appsFlyerConversionSnapshot(data: Map<String, Any>, afUid: String): JsonObject = buildJsonObject {
    (conversionJson(data) as JsonObject).forEach { (key, value) -> put(key, value) }
    put("af_uid", afUid)
}.withoutAdvertisingIdentifiers()

internal fun appsFlyerAppOpenAttributionSnapshot(data: Map<String, String>, afUid: String): JsonObject = buildJsonObject {
    val hasSignal = data.hasAppOpenAttributionSignal()
    data.forEach { (key, value) ->
        if (hasSignal || key != "af_status") put(key, value)
    }
    put("af_callback_type", "app_open_attribution")
    if (hasSignal) {
        put("af_status", data["af_status"]?.takeIf { it.isNotBlank() } ?: "Non-organic")
    }
    put("af_uid", afUid)
}.withoutAdvertisingIdentifiers()

private fun Map<String, String>.hasAppOpenAttributionSignal(): Boolean =
    listOf("media_source", "network", "campaign", "deep_link_value", "af_dp", "link")
        .any { key -> this[key]?.isNotBlank() == true }
