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

    @Test
    fun `recharge stream uses generated runtime timing configuration`() {
        val configuration = coreRechargeStreamConfiguration()

        assertEquals(BuildConfig.RECHARGE_RETRY_INITIAL_MS.toLong(), configuration.initialRetry)
        assertEquals(BuildConfig.RECHARGE_RETRY_MAX_MS.toLong(), configuration.maxRetry)
        assertEquals(BuildConfig.RECHARGE_FIRST_MESSAGE_TIMEOUT_MS.toLong(), configuration.firstMessageTimeout)
        assertEquals(BuildConfig.RECHARGE_IDLE_TIMEOUT_MS.toLong(), configuration.idleTimeout)
        assertEquals(BuildConfig.RECHARGE_CHECK_INTERVAL_MS.toLong(), configuration.checkInterval)
        assertEquals(BuildConfig.RECHARGE_DEDUPE_WINDOW_MS.toLong(), configuration.dedupeWindow)
    }
}
