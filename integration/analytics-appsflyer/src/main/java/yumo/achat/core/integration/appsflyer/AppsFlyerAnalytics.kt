package yumo.achat.core.integration.appsflyer

import android.app.Activity
import android.content.Context
import com.appsflyer.AppsFlyerLib
import yumo.achat.core.analytics.AnalyticsPlatform
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.analytics.AttributionIdProvider

class AppsFlyerAnalytics(
    context: Context,
    private val config: AppsFlyerConfig,
) : AnalyticsSink, AttributionIdProvider {
    private val applicationContext = context.applicationContext
    private val foregroundStart = ForegroundAttributionStart<Activity>(
        valid = { !it.isFinishing && !it.isDestroyed },
        start = ::startWithActivity,
    )
    private var initialized = false
    private val pending = PendingAnalyticsBuffer()

    fun onActivityResumed(activity: Activity) = foregroundStart.attach(activity)
    fun onActivityPaused(activity: Activity) = foregroundStart.detach(activity)

    @Synchronized
    override fun initialize() {
        if (!config.enabled) return
        foregroundStart.request()
    }

    @Synchronized
    fun retry() {
        if (config.enabled) foregroundStart.request(retry = true)
    }

    @Synchronized
    private fun startWithActivity(activity: Activity): Boolean = runCatching {
        AppsFlyerLib.getInstance().apply {
            if (!initialized) {
                setDebugLog(config.sdkDebugLogging)
                init(config.devKey, null, applicationContext)
                initialized = true
            }
            start(activity, config.devKey)
            pending.drain().also { snapshot ->
                snapshot.events.forEach { event ->
                    if (event.identityWasSet) setCustomerUserId(event.identity.orEmpty())
                    logEvent(applicationContext, AnalyticsPlatform.APPS_FLYER.eventName(event.name), event.parameters)
                }
                if (snapshot.identityWasSet) setCustomerUserId(snapshot.identity.orEmpty())
            }
        }
        true
    }.getOrDefault(false)

    @Synchronized
    override fun identify(userId: String?) {
        if (initialized) AppsFlyerLib.getInstance().setCustomerUserId(userId.orEmpty()) else pending.identify(userId)
    }

    @Synchronized
    override fun event(name: String, parameters: Map<String, Any>) {
        if (initialized) {
            AppsFlyerLib.getInstance().logEvent(
                applicationContext,
                AnalyticsPlatform.APPS_FLYER.eventName(name),
                parameters,
            )
        } else pending.event(name, parameters)
    }

    @Synchronized
    override fun currentId(): String = if (initialized) {
        AppsFlyerLib.getInstance().getAppsFlyerUID(applicationContext).orEmpty()
    } else {
        ""
    }
}
