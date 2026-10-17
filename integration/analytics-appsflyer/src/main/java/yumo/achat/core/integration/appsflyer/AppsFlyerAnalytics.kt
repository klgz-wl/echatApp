package yumo.achat.core.integration.appsflyer

import android.app.Activity
import android.content.Context
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.AppsFlyerConversionListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
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

@Singleton
class AppsFlyerAnalytics @Inject constructor(@ApplicationContext private val context: Context,
    private val config: AppsFlyerConfig) : AnalyticsSink, AttributionIdProvider, ConversionSource {
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var sdkMediaObserved = false
    private val sdkLock = Any()
    private val pendingEvents = PendingAppsFlyerEvents(config.eventQueueCapacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStartStatus = MutableStateFlow(AttributionStartStatus(AttributionStartStage.NOT_REQUESTED))
    val startStatus = mutableStartStatus.asStateFlow()
    private val conversionState = AppsFlyerConversionState()
    override val conversion = conversionState.conversion
    private val foregroundStart = ForegroundAttributionStart<Activity>(
        valid = { !it.isFinishing && !it.isDestroyed }, start = ::startWithActivity,
        timeoutMillis = config.requestTimeoutMillis,
        minIntervalMillis = config.minSessionIntervalSeconds * 1_000L)
    private val gcdClient = GcdAttributionClient(config.gcd, context.packageName, config.devKey)
    private val gcd = GcdRecheckCoordinator<Activity>(
        scope = scope,
        minIntervalMillis = config.gcd.minIntervalMillis,
        timeoutMillis = config.gcd.timeoutMillis,
        nowNanos = System::nanoTime,
        uid = ::currentId,
        eligible = { ready && config.enabled && config.canCollect() && !sdkMediaObserved },
        validHost = { !it.isFinishing && !it.isDestroyed },
        fetch = gcdClient::fetch,
        onMedia = conversionState::httpMedia,
    )
    private val callbackSequencer = AppsFlyerConversionCallbackSequencer()
    private val callbackEvents = ConversionCallbackEvents<AppsFlyerConversionCallback>()
    val conversionCallbacks = callbackEvents.events
    private var gaidRequested = false
    @Volatile private var gaid: String? = null
    /** 仅在地区检查通过后，由 MainActivity 的 RESUMED 生命周期绑定。 */
    fun onActivityResumed(activity: Activity) { foregroundStart.attach(activity); gcd.attach(activity) }
    fun onActivityPaused(activity: Activity) { foregroundStart.detach(activity); gcd.detach(activity) }
    fun onAccessRevoked() { foregroundStart.revokeAccess(); gcd.revokeAccess() }

    override fun initialize() = startAttribution()
    override fun startAttribution() { requestStart(retry = false) }
    override fun retryAttribution() { requestStart(retry = true) }
    private fun requestStart(retry: Boolean) {
        scope.launch {
            if (!config.enabled || !config.canCollect()) {
                updateStartStatus(AttributionStartStage.DISABLED)
                conversionState.sdkFailure()
                return@launch
            }
            val known = conversionState.data
            if (known?.isMediaAttribution() == true) return@launch
            if (known?.hasValidAttribution() == true) {
                gcd.request()
                return@launch
            }
            if (mutableStartStatus.value.stage == AttributionStartStage.NOT_REQUESTED)
                updateStartStatus(AttributionStartStage.WAITING_FOR_ACTIVITY)
            foregroundStart.request(retry)
        }
    }

    private fun startWithActivity(activity: Activity, attempt: Long): Boolean {
        if (!gaidRequested) {
            gaidRequested = true
            scope.launch(Dispatchers.IO) {
                gaid = runCatching { AdvertisingIdClient.getAdvertisingIdInfo(context) }.getOrNull()?.let {
                    it.id?.takeUnless { id -> it.isLimitAdTrackingEnabled || id.isBlank() || id.all { c -> c == '0' || c == '-' } }
                }
            }
        }
        try {
            synchronized(sdkLock) { AppsFlyerLib.getInstance().apply {
                if (!ready) {
                    setDebugLog(config.debugLogging)
                    setMinTimeBetweenSessions(config.minSessionIntervalSeconds)
                    init(config.devKey, object : AppsFlyerConversionListener {
                        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                            val snapshot = appsFlyerConversionSnapshot(data.orEmpty(), currentId())
                            if (snapshot.isMediaAttribution()) sdkMediaObserved = true
                            val status = (snapshot["af_status"] as? JsonPrimitive)?.contentOrNull
                            val callbackAttempt = foregroundStart.latestAttempt
                            callbackEvents.publish(callbackSequencer.success(status, callbackAttempt))
                            scope.launch {
                                conversionState.sdkSnapshot(snapshot)
                                if (snapshot.isMediaAttribution()) gcd.sdkMedia()
                                else if (status.equals("Organic", ignoreCase = true)) {
                                    foregroundStart.dropPendingRetry()
                                    gcd.sdkOrganic()
                                }
                                foregroundStart.conversionFinished(callbackAttempt)
                                diagnostic("AppsFlyer/Attribution", "收到归因回调，有效=${snapshot.hasValidAttribution()}")
                            }
                        }
                        override fun onConversionDataFail(message: String?) {
                            val callbackAttempt = foregroundStart.latestAttempt
                            callbackEvents.publish(callbackSequencer.failure(callbackAttempt))
                            scope.launch {
                                conversionState.sdkFailure()
                                foregroundStart.conversionFinished(callbackAttempt)
                            }
                            // 仅提取 SDK 已知格式中的 HTTP 数字，不输出任意回调原文。
                            val status = Regex("^Launch status code: ([0-9]{3})$")
                                .matchEntire(message.orEmpty().trim())?.groupValues?.get(1)
                            diagnostic("AppsFlyer/Attribution", "SDK 归因回调失败，HTTP=${status ?: "unknown"}")
                        }
                        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
                            val snapshot = appsFlyerAppOpenAttributionSnapshot(data.orEmpty(), currentId())
                            if (snapshot.isMediaAttribution()) sdkMediaObserved = true
                            if (snapshot.hasValidAttribution()) scope.launch {
                                conversionState.sdkSnapshot(snapshot)
                                if (snapshot.isMediaAttribution()) gcd.sdkMedia()
                            }
                        }
                        override fun onAttributionFailure(message: String?) { /* 保留已有安装归因。 */ }
                    }, context)
                    ready = true
                }
                conversionState.startPending()
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
            foregroundStart.launchFailed(attempt)
            updateStartStatus(AttributionStartStage.INITIALIZATION_FAILED)
            conversionState.sdkFailure()
            return false
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
    override fun advertisingId(): String? = gaid
    override fun attributionUid(): String = currentId()
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
}

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
}

private fun Map<String, String>.hasAppOpenAttributionSignal(): Boolean =
    listOf("media_source", "network", "campaign", "deep_link_value", "af_dp", "link")
        .any { key -> this[key]?.isNotBlank() == true }
