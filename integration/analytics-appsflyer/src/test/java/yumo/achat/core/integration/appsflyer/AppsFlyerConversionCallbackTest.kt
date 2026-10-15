package yumo.achat.core.integration.appsflyer

import org.junit.Assert.*
import org.junit.Test

class AppsFlyerConversionCallbackTest {
    @Test fun `each real callback receives a new process sequence even for identical Organic data`() {
        val sequencer = AppsFlyerConversionCallbackSequencer()
        val first = sequencer.success("Organic", 4)
        val second = sequencer.success("Organic", 4)
        assertEquals(1L, first.callbackId)
        assertEquals(2L, second.callbackId)
        assertEquals("organic", first.attributionStatus)
        assertEquals("organic", second.attributionStatus)
        assertEquals(4L, second.requestAttempt)
    }
    @Test fun `unknown conversion is a controlled failure`() {
        val callback = AppsFlyerConversionCallbackSequencer().success("mystery", 2)
        assertEquals("invalid_conversion", callback.failureReason)
        assertNull(callback.attributionStatus)
    }
    @Test fun `SDK conversion failure does not leak raw error`() {
        val callback = AppsFlyerConversionCallbackSequencer().failure(3)
        assertEquals("conversion_callback_failed", callback.failureReason)
        assertEquals(3L, callback.requestAttempt)
    }
}
