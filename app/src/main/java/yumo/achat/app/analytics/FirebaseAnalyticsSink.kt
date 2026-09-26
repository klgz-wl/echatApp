package yumo.achat.app.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import yumo.achat.core.analytics.AnalyticsPlatform
import yumo.achat.core.analytics.AnalyticsSink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal interface FirebaseClient {
    fun initialize()
    fun identify(userId: String?)
    fun event(name: String, parameters: Map<String, Any>)
    fun setUserProperty(name: String, value: String?)
}

internal class FirebaseAnalyticsSink(
    private val client: FirebaseClient,
    private val analyticsEnabled: Boolean = true,
) : AnalyticsSink {
    override fun initialize() = client.initialize()
    override fun identify(userId: String?) = client.identify(userId)
    override fun event(name: String, parameters: Map<String, Any>) {
        if (analyticsEnabled) client.event(AnalyticsPlatform.FIREBASE.eventName(name), parameters)
    }

    fun recordCampaignAttribution(data: JsonObject) {
        fun value(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        mapOf(
            "attribution_status" to value("af_status"),
            "media_source" to value("media_source"),
            "campaign" to value("campaign"),
            "channel" to value("af_channel"),
            "adset" to value("af_adset"),
            "ad" to value("af_ad"),
        ).forEach(client::setUserProperty)
        client.event(
            FirebaseAnalytics.Event.CAMPAIGN_DETAILS,
            buildMap {
                value("campaign")?.let { put("campaign", it) }
                value("media_source")?.let { put("source", it) }
                value("af_channel")?.let { put("medium", it) }
                value("af_ad")?.let { put("content", it) }
                value("af_adset")?.let { put("ad_group", it) }
                value("af_status")?.let { put("af_status", it) }
            },
        )
    }
}

internal class FirebaseSdkClient(
    context: Context,
    private val analyticsEnabled: Boolean,
    private val crashlyticsEnabled: Boolean,
    private val messagingEnabled: Boolean,
) : FirebaseClient {
    private val appContext = context.applicationContext
    private val analytics by lazy { FirebaseAnalytics.getInstance(appContext) }

    override fun initialize() {
        FirebaseApp.initializeApp(appContext)
        if (analyticsEnabled) analytics.setAnalyticsCollectionEnabled(true)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = crashlyticsEnabled
        FirebaseMessaging.getInstance().isAutoInitEnabled = messagingEnabled
    }

    override fun identify(userId: String?) {
        if (analyticsEnabled) analytics.setUserId(userId)
        if (crashlyticsEnabled) FirebaseCrashlytics.getInstance().setUserId(userId.orEmpty())
    }

    override fun event(name: String, parameters: Map<String, Any>) {
        if (!analyticsEnabled) return
        analytics.logEvent(name, Bundle().apply {
            parameters.forEach { (key, value) ->
                when (value) {
                    is Double -> putDouble(key, value)
                    is Float -> putDouble(key, value.toDouble())
                    is Number -> putLong(key, value.toLong())
                    else -> putString(key, value.toString())
                }
            }
        })
    }

    override fun setUserProperty(name: String, value: String?) {
        if (analyticsEnabled) analytics.setUserProperty(name, value)
    }
}
