package yumo.achat.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentEventQueueTest {
    @Test fun `三方支付客户端事件映射为数数埋点`() {
        val expected = mapOf(
            "link_ok" to "3rdpayment_link_ok",
            "link_error" to "3rdpayment_link_error",
            "page_opened" to "3rdpayment_page_opened",
            "page_loaded" to "3rdpayment_page_loaded",
            "page_load_error" to "3rdpayment_page_load_error",
            "paid_while_open" to "3rdpayment_paid_while_open",
            "paid_on_close" to "3rdpayment_paid_on_close",
            "user_cancel" to "3rdpayment_user_cancel",
            "timeout_cancel" to "3rdpayment_timeout_cancel",
        )

        expected.forEach { (eventType, analyticsName) ->
            assertEquals(analyticsName, thirdPartyPaymentAnalyticsName(eventType))
        }
        assertNull(thirdPartyPaymentAnalyticsName("poll_timeout"))
    }

    @Test fun `未知三方支付渠道字段使用非空诊断占位避免后台丢弃事件`() {
        assertEquals("unknown", thirdPartyPaymentDiagnosticValue(null))
        assertEquals("unknown", thirdPartyPaymentDiagnosticValue(""))
        assertEquals("payu_web", thirdPartyPaymentDiagnosticValue(" payu_web "))
    }

    @Test fun `三方支付paid诊断事件不使用主支付成功口径`() {
        assertEquals(ThirdPartyPaymentAnalyticsOutcome("paid", "fulfilled"), thirdPartyPaymentAnalyticsOutcome("paid_while_open"))
        assertEquals(ThirdPartyPaymentAnalyticsOutcome("paid", "fulfilled"), thirdPartyPaymentAnalyticsOutcome("paid_on_close"))
        assertEquals(ThirdPartyPaymentAnalyticsOutcome("failed", "link_error"), thirdPartyPaymentAnalyticsOutcome("link_error"))
        assertEquals(ThirdPartyPaymentAnalyticsOutcome("unknown", "poll_timeout"), thirdPartyPaymentAnalyticsOutcome("timeout_cancel"))
    }
}
