package yumo.achat.core.integration.appsflyer

import android.app.Activity
import android.content.Context
import com.appsflyer.attribution.AppsFlyerRequestListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.AppsFlyerConversionListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import yumo.achat.core.attribution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import timber.log.Timber
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.analytics.AttributionIdProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppsFlyerAnalytics @Inject constructor(@ApplicationContext private val context: Context,
    private val config: AppsFlyerConfig) : AnalyticsSink, AttributionIdProvider, ConversionSource {
    @Volatile private var ready = false
    @Volatile private var started = false
    private val sdkLock = Any()
    private val pendingEvents = PendingAppsFlyerEvents(config.eventQueueCapacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStartStatus = MutableStateFlow(AttributionStartStatus(AttributionStartStage.NOT_REQUESTED))
    val startStatus = mutableStartStatus.asStateFlow()
    private val foregroundStart = ForegroundAttributionStart<Activity>(
        valid = { !it.isFinishing && !it.isDestroyed }, start = ::startWithActivity)
    private var gaidRequested = false
    private val mutable = MutableStateFlow<ConversionResult>(ConversionResult.Pending)
    override val conversion = mutable.asStateFlow()
    @Volatile private var gaid: String? = null
    /** 仅在地区检查通过后，由 MainActivity 的 RESUMED 生命周期绑定。 */
    fun onActivityResumed(activity: Activity) { foregroundStart.attach(activity) }
    fun onActivityPaused(activity: Activity) { foregroundStart.detach(activity) }

    override fun initialize() = startAttribution()
    override fun startAttribution() { requestStart(retry = false) }
    override fun retryAttribution() { requestStart(retry = true) }
    private fun requestStart(retry: Boolean) {
        scope.launch {
            if (!config.enabled) {
                updateStartStatus(AttributionStartStage.DISABLED)
                mutable.value = ConversionResult.Failed
                return@launch
            }
            if (retry && mutable.value is ConversionResult.Success) return@launch
            if (mutableStartStatus.value.stage == AttributionStartStage.NOT_REQUESTED)
                updateStartStatus(AttributionStartStage.WAITING_FOR_ACTIVITY)
            foregroundStart.request(retry)
        }
    }

    private fun startWithActivity(activity: Activity): Boolean {
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
                    init(config.devKey, object : AppsFlyerConversionListener {
                        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
                            val snapshot = buildJsonObject {
                                (conversionJson(data.orEmpty()) as JsonObject).forEach { (key, value) -> put(key, value) }
                                put("af_uid", currentId())
                            }
                            mutable.value = if (!snapshot.hasValidAttribution()) ConversionResult.Failed else ConversionResult.Success(snapshot)
                            diagnostic("AppsFlyer/Attribution", "收到归因回调，有效=${snapshot.hasValidAttribution()}")
                        }
                        override fun onConversionDataFail(message: String?) {
                            if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Failed
                            // 仅提取 SDK 已知格式中的 HTTP 数字，不输出任意回调原文。
                            val status = Regex("^Launch status code: ([0-9]{3})$")
                                .matchEntire(message.orEmpty().trim())?.groupValues?.get(1)
                            diagnostic("AppsFlyer/Attribution", "SDK 归因回调失败，HTTP=${status ?: "unknown"}")
                        }
                        override fun onAppOpenAttribution(data: MutableMap<String, String>?) { /* 深链不覆盖安装归因。 */ }
                        override fun onAttributionFailure(message: String?) { /* 保留已有安装归因。 */ }
                    }, context)
                    ready = true
                }
                if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Pending
                updateStartStatus(AttributionStartStage.START_CALLED)
                // 延迟启动必须传当前 Activity；Application Context 会遗漏已经发生的首次前台事件。
                start(activity, config.devKey, object : AppsFlyerRequestListener {
                    override fun onSuccess() {
                        updateStartStatus(AttributionStartStage.REQUEST_ACCEPTED)
                        // 请求被接收不等于取得安装归因，conversion 只由 ConversionListener 更新。
                    }
                    override fun onError(code: Int, description: String) {
                        updateStartStatus(AttributionStartStage.REQUEST_FAILED, code)
                        if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Failed
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
            updateStartStatus(AttributionStartStage.INITIALIZATION_FAILED)
            if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Failed
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
