package yumo.achat.app.analytics

import android.content.Context

internal object AnalyticsConsent {
    private const val PREFERENCES = "achat_privacy_consent"
    private const val KEY_DECIDED = "analytics_decided"
    private const val KEY_GRANTED = "analytics_granted"

    fun decision(context: Context): Boolean? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return if (preferences.getBoolean(KEY_DECIDED, false)) {
            preferences.getBoolean(KEY_GRANTED, false)
        } else null
    }

    fun save(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DECIDED, true)
            .putBoolean(KEY_GRANTED, granted)
            .apply()
    }

    fun granted(context: Context): Boolean = decision(context) == true
}
