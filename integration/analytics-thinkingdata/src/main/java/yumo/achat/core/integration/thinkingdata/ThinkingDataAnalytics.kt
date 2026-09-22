package yumo.achat.core.integration.thinkingdata

import android.content.Context
import cn.thinkingdata.analytics.TDAnalytics
import cn.thinkingdata.analytics.TDConfig
import org.json.JSONObject
import yumo.achat.core.analytics.AnalyticsPlatform
import yumo.achat.core.analytics.AnalyticsSink

class ThinkingDataAnalytics(
    context: Context,
    private val config: ThinkingDataConfig,
) : AnalyticsSink {
    private val applicationContext = context.applicationContext
    @Volatile private var initialized = false

    @Synchronized
    override fun initialize() {
        if (!config.enabled || initialized) return
        val sdkConfig = TDConfig.getInstance(applicationContext, config.appId, config.serverUrl)
        if (config.debugMode) sdkConfig.setMode(TDConfig.ModeEnum.DEBUG)
        TDAnalytics.init(sdkConfig)
        initialized = true
    }

    @Synchronized
    override fun identify(userId: String?) {
        if (!initialized) return
        if (userId == null) TDAnalytics.logout() else TDAnalytics.login(userId)
    }

    @Synchronized
    override fun event(name: String, parameters: Map<String, Any>) {
        if (initialized) TDAnalytics.track(AnalyticsPlatform.THINKING_DATA.eventName(name), JSONObject(parameters))
    }
}
