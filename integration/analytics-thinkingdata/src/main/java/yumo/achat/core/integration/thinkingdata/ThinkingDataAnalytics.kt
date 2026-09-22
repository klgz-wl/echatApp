package yumo.achat.core.integration.thinkingdata

import android.content.Context
import cn.thinkingdata.analytics.TDAnalytics
import cn.thinkingdata.analytics.TDConfig
import cn.thinkingdata.thirdparty.TDThirdPartyShareType
import yumo.achat.core.analytics.AnalyticsSink
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThinkingDataAnalytics @Inject constructor(@ApplicationContext private val context: Context,
    private val config: ThinkingDataConfig, private val policy: yumo.achat.core.analytics.AnalyticsPolicy) : AnalyticsSink {
    override fun initialize() {
        val sdkConfig = TDConfig.getInstance(context, config.appId, config.serverUrl)
        if (config.debugMode) sdkConfig.setMode(TDConfig.ModeEnum.DEBUG)
        TDAnalytics.init(sdkConfig)
        TDAnalytics.enableAutoTrack(TDAnalytics.TDAutoTrackEventType.APP_START or TDAnalytics.TDAutoTrackEventType.APP_END
            or TDAnalytics.TDAutoTrackEventType.APP_INSTALL or TDAnalytics.TDAutoTrackEventType.APP_VIEW_SCREEN
            or TDAnalytics.TDAutoTrackEventType.APP_CLICK or TDAnalytics.TDAutoTrackEventType.APP_CRASH)
        TDAnalytics.trackFragmentAppViewScreen()
        shareIdentity()
    }
    override fun identify(userId: String?) {
        if (userId == null) TDAnalytics.logout() else TDAnalytics.login(userId)
        shareIdentity()
    }
    override fun event(name: String, parameters: Map<String, Any>) { TDAnalytics.track(yumo.achat.core.analytics.AnalyticsPlatform.THINKING_DATA.eventName(name), JSONObject(parameters)) }
    private fun shareIdentity() { if (policy.appsFlyer) TDAnalytics.enableThirdPartySharing(TDThirdPartyShareType.TD_APPS_FLYER) }
}
