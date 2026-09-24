package yumo.achat.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import yumo.achat.core.region.IpRegionSource
import yumo.achat.core.region.LocalRegion
import yumo.achat.core.region.LocalRegionKind
import yumo.achat.core.region.LocalRegionSource
import yumo.achat.core.region.RegionAccess
import yumo.achat.core.region.RegionAccessConfiguration
import yumo.achat.core.region.RegionNetworkSource
import yumo.achat.core.region.RegionRestrictedException

internal data class RegionGateState(
    val allowed: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val restricted: Boolean = false,
)

internal class RegionGateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(RegionGateState())
    val state = mutableState.asStateFlow()
    private val appContext = application.applicationContext
    private val access = RegionAccess(
        configuration = RegionAccessConfiguration(
            enabled = BuildConfig.ENABLE_REGION_RESTRICTION,
            blockedCountries = BuildConfig.BLOCKED_REGION_CODES.split(',').map(String::trim).filter(String::isNotBlank).toSet(),
            ipRetryAttempts = BuildConfig.REGION_LOOKUP_RETRY_ATTEMPTS,
            ipRetryDelayMillis = BuildConfig.REGION_LOOKUP_RETRY_DELAY_MS.toLong(),
        ),
        local = LocalRegionSource {
            val telephony = appContext.getSystemService(TelephonyManager::class.java)
            buildList {
                telephony?.simCountryIso?.takeIf(String::isNotBlank)?.let {
                    add(LocalRegion(LocalRegionKind.SIM, it))
                }
                Locale.getDefault().country.takeIf(String::isNotBlank)?.let {
                    add(LocalRegion(LocalRegionKind.SYSTEM, it))
                }
            }
        },
        ip = IpRegionSource {
            withContext(Dispatchers.IO) {
                val connection = (URL(BuildConfig.REGION_LOOKUP_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = BuildConfig.REGION_LOOKUP_TIMEOUT_MS
                    readTimeout = BuildConfig.REGION_LOOKUP_TIMEOUT_MS
                    requestMethod = "GET"
                }
                try {
                    check(connection.responseCode in 200..299) { "Region lookup failed" }
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("country")
                } finally {
                    connection.disconnect()
                }
            }
        },
        network = RegionNetworkSource {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        },
    )

    fun check() {
        if (state.value.busy || state.value.allowed) return
        mutableState.value = RegionGateState(busy = true)
        viewModelScope.launch {
            try {
                access.check()
                mutableState.value = RegionGateState(allowed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RegionRestrictedException) {
                mutableState.value = RegionGateState(restricted = true)
            } catch (_: Exception) {
                mutableState.value = RegionGateState(error = true)
            }
        }
    }
}
