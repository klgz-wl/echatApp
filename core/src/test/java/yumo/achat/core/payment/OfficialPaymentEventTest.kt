package yumo.achat.core.payment

import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.billing.BillingResponseCode

class OfficialPaymentEventTest {
    @Test fun `Play取消与失败分别映射为诊断事件而成功不映射为支付成功`() {
        assertEquals("official_sdk_cancelled", officialSdkResultEvent(BillingResponseCode.CANCELLED))
        assertEquals("official_sdk_failed", officialSdkResultEvent(BillingResponseCode.BILLING_UNAVAILABLE))
        assertNull(officialSdkResultEvent(BillingResponseCode.OK))
    }
}
