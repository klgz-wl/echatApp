package yumo.achat.core.integration.appsflyer

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class InstallReferrerAttributionClient(
    context: Context,
    storageName: String,
    private val paidMediums: Set<String>,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun supplement(afUid: String): kotlinx.serialization.json.JsonObject? {
        val raw = cached() ?: queryAndCache() ?: return null
        return installReferrerSupplement(raw, afUid, paidMediums)
    }

    private fun cached(): InstallReferrerRaw? {
        val firstInstallTime = firstInstallTime()
        if (preferences.getLong(KEY_FIRST_INSTALL_TIME, Long.MIN_VALUE) != firstInstallTime) return null
        return preferences.getString(KEY_RESULT, null)?.let {
            runCatching { json.decodeFromString<InstallReferrerRaw>(it) }.getOrNull()
        }
    }

    private suspend fun queryAndCache(): InstallReferrerRaw? = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(appContext).build()
        continuation.invokeOnCancellation { runCatching { client.endConnection() } }
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val raw = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching {
                        client.installReferrer.let { details ->
                            InstallReferrerRaw(
                                installReferrer = details.installReferrer.orEmpty(),
                                referrerClickTimestampSeconds = details.referrerClickTimestampSeconds,
                                installBeginTimestampSeconds = details.installBeginTimestampSeconds,
                                referrerClickTimestampServerSeconds = details.referrerClickTimestampServerSeconds,
                                installBeginTimestampServerSeconds = details.installBeginTimestampServerSeconds,
                                installVersion = details.installVersion,
                                googlePlayInstant = details.googlePlayInstantParam,
                            )
                        }
                    }.getOrNull()
                } else null
                val stored = raw?.takeIf {
                    preferences.edit()
                        .putLong(KEY_FIRST_INSTALL_TIME, firstInstallTime())
                        .putString(KEY_RESULT, json.encodeToString(it))
                        .commit()
                }
                runCatching { client.endConnection() }
                if (continuation.isActive) continuation.resume(stored)
            }

            override fun onInstallReferrerServiceDisconnected() {
                runCatching { client.endConnection() }
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun firstInstallTime(): Long = appContext.packageManager
        .getPackageInfo(appContext.packageName, 0).firstInstallTime

    private companion object {
        const val KEY_FIRST_INSTALL_TIME = "first_install_time_ms"
        const val KEY_RESULT = "successful_result"
    }
}
