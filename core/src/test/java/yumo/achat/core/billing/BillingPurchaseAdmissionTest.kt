package yumo.achat.core.billing

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BillingPurchaseAdmissionTest {
    @Test
    fun `one lease gates in app and subscription purchases process wide`() {
        val admission = BillingPurchaseAdmission()
        val first = admission.tryAcquire()

        assertNotNull(first)
        assertNull(admission.tryAcquire())

        first!!.close()
        assertNotNull(admission.tryAcquire())
    }
}
