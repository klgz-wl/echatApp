package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.payment.*

class OfficialCheckoutReturnTest {
    private fun order() = PaymentRecord("order-key", "owner", "pack", "main",
        productType = "diamond", stage = PaymentStage.OFFICIAL_LAUNCHED,
        initialized = InitializedPayment("order-id", "official", "google_play", "sdk"))
    @Test fun pauseResumeWithoutStopRecoversOnlyCapturedOrderOnce() {
        val tracker = OfficialCheckoutReturn()
        tracker.paused("epoch-1", order())
        assertEquals("order-key", tracker.resumed("epoch-1"))
        assertNull(tracker.resumed("epoch-1"))
    }
    @Test fun accountSwitchAndUnrelatedCheckoutCannotTriggerOfficialReturn() {
        val tracker = OfficialCheckoutReturn()
        tracker.paused("epoch-1", order())
        assertNull(tracker.resumed("epoch-2"))
        tracker.paused("epoch-1", order().copy(productType = "membership"))
        assertNull(tracker.resumed("epoch-1"))
        tracker.paused("epoch-1", order().copy(initialized = InitializedPayment("order-id", "third_party", "payu", "webview")))
        assertNull(tracker.resumed("epoch-1"))
    }
}
