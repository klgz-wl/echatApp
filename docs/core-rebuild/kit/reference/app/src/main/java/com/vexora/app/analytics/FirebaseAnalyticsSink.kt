package com.vexora.app.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.vexora.core.analytics.AnalyticsSink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAnalyticsSink @Inject constructor(@ApplicationContext private val context: Context) : AnalyticsSink {
    private val analytics by lazy { FirebaseAnalytics.getInstance(context) }
    override fun initialize() { analytics }
    override fun identify(userId: String?) {
        analytics.setUserId(userId)
        FirebaseCrashlytics.getInstance().setUserId(userId.orEmpty())
    }
    override fun event(name: String, parameters: Map<String, Any>) {
        analytics.logEvent(com.vexora.core.analytics.AnalyticsPlatform.FIREBASE.eventName(name), Bundle().apply { parameters.forEach { (key, value) ->
            when (value) {
                is Double -> putDouble(key, value)
                is Float -> putDouble(key, value.toDouble())
                is Long -> putLong(key, value)
                is Int -> putLong(key, value.toLong())
                else -> putString(key, value.toString())
            }
        } })
    }
}
