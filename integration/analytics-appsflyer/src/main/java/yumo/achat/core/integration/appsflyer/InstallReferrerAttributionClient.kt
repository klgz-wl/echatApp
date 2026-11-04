package yumo.achat.core.integration.appsflyer

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

internal class InstallReferrerAttributionClient(
    context: Context,
    storageName: String,
    private val paidMediums: Set<String>,
) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(storageName, Context.MODE_PRIVATE) }
    private val reader = InstallReferrerReadCoordinator(
        cache = InstallReferrerPersistentCache(object : InstallReferrerCacheStorage {
            override fun successfulResult() = preferences.getString(KEY_RESULT, null)
            override fun legacyFirstInstallTime() = preferences.getLong(KEY_FIRST_INSTALL_TIME, Long.MIN_VALUE)
            override fun commitCanonical(encoded: String, isActive: () -> Boolean): Boolean {
                val edit = preferences.edit().putString(KEY_RESULT, encoded)
                    .remove(KEY_FIRST_INSTALL_TIME)
                return isActive() && edit.commit()
            }
        }, ::firstInstallTime),
        connect = {
            val client = InstallReferrerClient.newBuilder(appContext).build()
            object : InstallReferrerConnection {
                override fun start(onSetup: (Boolean) -> Unit, onDisconnected: () -> Unit) {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            onSetup(responseCode == InstallReferrerClient.InstallReferrerResponse.OK)
                        }
                        override fun onInstallReferrerServiceDisconnected() = onDisconnected()
                    })
                }
                override fun read() = client.installReferrer.let { details ->
                    InstallReferrerRaw(
                        installReferrer = details.installReferrer,
                        referrerClickTimestampSeconds = details.referrerClickTimestampSeconds,
                        installBeginTimestampSeconds = details.installBeginTimestampSeconds,
                        referrerClickTimestampServerSeconds = details.referrerClickTimestampServerSeconds,
                        installBeginTimestampServerSeconds = details.installBeginTimestampServerSeconds,
                        installVersion = details.installVersion,
                        googlePlayInstant = details.googlePlayInstantParam,
                    )
                }
                override fun close() = client.endConnection()
            }
        },
    )

    suspend fun supplement(afUid: String): kotlinx.serialization.json.JsonObject? {
        val raw = reader.read() ?: return null
        return installReferrerSupplement(raw, afUid, paidMediums)
    }

    @Suppress("DEPRECATION")
    private fun firstInstallTime(): Long? = runCatching { appContext.packageManager
        .getPackageInfo(appContext.packageName, 0).firstInstallTime.takeIf { it > 0 } }.getOrNull()

    private companion object {
        const val KEY_FIRST_INSTALL_TIME = "first_install_time_ms"
        const val KEY_RESULT = "successful_result"
    }
}
