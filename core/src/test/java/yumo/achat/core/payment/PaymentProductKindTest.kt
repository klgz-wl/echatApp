package yumo.achat.core.payment

import org.junit.Assert.*
import org.junit.Test

class PaymentProductKindTest {
    @Test fun membershipRecordsNeverBelongToRechargeRecovery() {
        val record = PaymentRecord("key", "owner", "membership", "restored", stage = PaymentStage.OFFICIAL_LAUNCHED,
            productType = "membership", initialized = InitializedPayment("order", "official", "google_play", "sdk", PaymentSdkParams("sku")))
        assertFalse(record.unresolved)
        assertFalse(shouldAutoPollPayment(record))
    }
}
