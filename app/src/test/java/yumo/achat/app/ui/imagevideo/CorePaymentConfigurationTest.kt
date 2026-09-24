package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.app.BuildConfig

class CorePaymentConfigurationTest {
    @Test
    fun `payment runtime uses independent service base url and backend fulfillment`() {
        val configuration = corePaymentConfiguration()

        assertEquals(BuildConfig.PAYMENT_BASE_URL, configuration.payment.baseUrl)
        assertNotEquals(BuildConfig.ACHAT_API_BASE_URL, configuration.payment.baseUrl)
        assertTrue(configuration.billing.backendOwnedFulfillment)
        assertEquals("top_up", configuration.billing.defaultTrigger)
    }
}
