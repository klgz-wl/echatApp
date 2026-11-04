package yumo.achat.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import yumo.achat.core.region.CountryIsRegionSource
import yumo.achat.core.region.LocalRegion
import yumo.achat.core.region.LocalRegionKind
import yumo.achat.core.region.LocalRegionSource
import yumo.achat.core.region.RegionAccess
import yumo.achat.core.region.RegionAccessConfiguration
import yumo.achat.core.region.RegionNetworkSource
import yumo.achat.core.region.RegionOfflineException
import yumo.achat.core.region.RegionRestrictedException
import yumo.achat.core.region.RegionUnavailableException

internal data class RegionGateState(
    val allowed: Boolean = false,
    val busy: Boolean = false,
    val error: Boolean = false,
    val restricted: Boolean = false,
    val offline: Boolean = false,
    val diagnosticCode: String? = null,
)

internal fun shouldEnableSecureWindow(configured: Boolean, debug: Boolean): Boolean = configured && !debug
internal fun shouldEnableRegionRestriction(configured: Boolean, debug: Boolean): Boolean = configured && !debug
internal fun regionNetworkConnected(hasInternet: Boolean, captivePortal: Boolean): Boolean =
    hasInternet && !captivePortal

internal fun regionGateFailureState(error: Throwable): RegionGateState = when (error) {
    is RegionRestrictedException -> RegionGateState(restricted = true, diagnosticCode = error.diagnosticCode)
    is RegionOfflineException -> RegionGateState(error = true, offline = true, diagnosticCode = error.diagnosticCode)
    is RegionUnavailableException -> RegionGateState(error = true, diagnosticCode = error.diagnosticCode)
    else -> RegionGateState(error = true, diagnosticCode = "R299")
}

internal class RegionGateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(RegionGateState())
    val state = mutableState.asStateFlow()
    private val appContext = application.applicationContext
    private val access = RegionAccess(
        configuration = RegionAccessConfiguration(
            enabled = shouldEnableRegionRestriction(BuildConfig.ENABLE_REGION_RESTRICTION, BuildConfig.DEBUG),
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
                appContext.resources.configuration.locales.get(0).country.takeIf(String::isNotBlank)?.let {
                    add(LocalRegion(LocalRegionKind.SYSTEM, it))
                }
            }
        },
        ip = CountryIsRegionSource(BuildConfig.REGION_LOOKUP_URL, BuildConfig.REGION_LOOKUP_TIMEOUT_MS.toLong()),
        network = RegionNetworkSource {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            regionNetworkConnected(
                hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            )
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
            } catch (error: Exception) {
                mutableState.value = regionGateFailureState(error)
            }
        }
    }
}
