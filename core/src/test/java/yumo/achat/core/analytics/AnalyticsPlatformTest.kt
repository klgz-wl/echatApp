package yumo.achat.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsPlatformTest {
    @Test
    fun `each analytics destination owns a stable event prefix`() {
        assertEquals("f_purchase", AnalyticsPlatform.FIREBASE.eventName("purchase"))
        assertEquals("a_purchase", AnalyticsPlatform.APPS_FLYER.eventName("purchase"))
        assertEquals("s_purchase", AnalyticsPlatform.THINKING_DATA.eventName("purchase"))
        assertEquals("z_purchase", AnalyticsPlatform.BACKEND.eventName("purchase"))
    }
}
