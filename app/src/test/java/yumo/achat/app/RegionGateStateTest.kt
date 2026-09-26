package yumo.achat.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.region.RegionOfflineException
import yumo.achat.core.region.RegionRestrictedException
import yumo.achat.core.region.RegionUnavailableException

class RegionGateStateTest {
    private val projectRoot: File
        get() = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)

    @Test
    fun `release protections require configured non debug build`() {
        assertTrue(shouldEnableSecureWindow(configured = true, debug = false))
        assertTrue(shouldEnableRegionRestriction(configured = true, debug = false))
        assertFalse(shouldEnableSecureWindow(configured = true, debug = true))
        assertFalse(shouldEnableRegionRestriction(configured = true, debug = true))
    }

    @Test
    fun `region failures preserve diagnostic and offline presentation`() {
        val restricted = regionGateFailureState(RegionRestrictedException("R103"))
        val offline = regionGateFailureState(RegionOfflineException())
        val unavailable = regionGateFailureState(RegionUnavailableException("R205-503"))

        assertTrue(restricted.restricted)
        assertEquals("R103", restricted.diagnosticCode)
        assertTrue(offline.offline)
        assertEquals("R200", offline.diagnosticCode)
        assertTrue(unavailable.error)
        assertEquals("R205-503", unavailable.diagnosticCode)
    }

    @Test
    fun `captive portal is not treated as connected internet`() {
        assertTrue(regionNetworkConnected(hasInternet = true, captivePortal = false))
        assertFalse(regionNetworkConnected(hasInternet = true, captivePortal = true))
        assertFalse(regionNetworkConnected(hasInternet = false, captivePortal = false))
    }

    @Test
    fun `active host uses hardened region source and secure task exit`() {
        val gate = projectRoot.resolve("app/src/main/java/yumo/achat/app/RegionGateViewModel.kt").readText()
        val activity = projectRoot.resolve("app/src/main/java/yumo/achat/app/MainActivity.kt").readText()

        assertTrue(gate.contains("CountryIsRegionSource"))
        assertFalse(gate.contains("HttpURLConnection"))
        assertTrue(activity.contains("FLAG_SECURE"))
        assertTrue(activity.contains("finishAndRemoveTask"))
    }
}
