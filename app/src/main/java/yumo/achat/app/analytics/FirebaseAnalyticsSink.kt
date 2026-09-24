package yumo.achat.app.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import yumo.achat.core.analytics.AnalyticsPlatform
import yumo.achat.core.analytics.AnalyticsSink

internal interface FirebaseClient {
    fun initialize()
    fun identify(userId: String?)
    fun event(name: String, parameters: Map<String, Any>)
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
}
