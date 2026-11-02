package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.app.R
import yumo.achat.core.billing.CoinPurchaseState
import yumo.achat.core.payment.InitializedPayment
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState

class TopUpPaymentRecoveryPresentationTest {
    @Test fun `review required displays localized support guidance and never starts checkout`() {
        val state = PaymentViewState(epoch = "epoch", recoveryLookupFailed = true,
            recoveryLookupError = "payment_review_required")
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState()) as TopUpCheckoutState.Failed
        assertEquals(R.string.top_up_payment_review_required, checkout.messageRes)
        assertFalse(state.checkoutVisible)
    }
    @Test fun `server recovered order displays confirmation and explicit retry before checkout`() {
        val state = payment(PaymentStage.UNCERTAIN, null).let {
            it.copy(record = it.record!!.copy(recoveryRequired = true))
        }
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState())
        assertTrue(checkout is TopUpCheckoutState.Failed)
        assertTrue((checkout as TopUpCheckoutState.Failed).recoverable)
        assertTrue(corePaymentPresentation(state).canRetry)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
    }

    @Test fun `failed server lookup presents retry even without a local order`() {
        val state = PaymentViewState(epoch = "epoch", recoveryLookupFailed = true)
        assertTrue(state.toTopUpCheckoutState(CoinPurchaseState()) is TopUpCheckoutState.Failed)
        assertTrue(corePaymentPresentation(state).canStartPurchase)
    }
    @Test fun `network failure offers same order recovery instead of checkout ready`() {
        val state = payment(PaymentStage.UNCERTAIN, "network_error")
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState())
        assertTrue(checkout is TopUpCheckoutState.Failed)
        checkout as TopUpCheckoutState.Failed
        assertTrue(checkout.recoverable)
        assertEquals(R.string.top_up_payment_network_error, checkout.messageRes)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
        assertTrue(corePaymentPresentation(state).canRetry)
    }

    @Test fun `confirmed card failures show provider card recovery guidance`() {
        val checkout = payment(PaymentStage.FAILED, "card_declined", "change_card")
            .toTopUpCheckoutState(CoinPurchaseState()) as TopUpCheckoutState.Failed
        assertEquals(R.string.top_up_card_declined, checkout.messageRes)
        assertEquals(R.string.top_up_use_another_card, checkout.actionRes)
        assertFalse(checkout.recoverable)
    }

    @Test fun `unknown provider text cannot become a card instruction`() {
        assertEquals(R.string.top_up_payment_failed, paymentIssueMessageRes("raw provider payload"))
        assertEquals(R.string.top_up_try_again, paymentRetryActionRes("unknown", "change_card"))
    }

    @Test fun `local timeout keeps unresolved order available for status retry`() {
        val state = payment(PaymentStage.TIMED_OUT, "expired_link")
        assertTrue(corePaymentPresentation(state).locksProductSelection)
        assertTrue(corePaymentPresentation(state).canRetry)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
    }

    @Test fun `offline close retains check network and same order retry`() {
        val state = payment(PaymentStage.CLOSED, "network_error")
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState()) as TopUpCheckoutState.Failed
        assertTrue(checkout.recoverable)
        assertEquals(R.string.top_up_payment_network_error, checkout.messageRes)
        assertTrue(corePaymentPresentation(state).canRetry)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
    }

    @Test fun `recoverable card problem keeps card action and blocks a replacement order`() {
        val state = payment(PaymentStage.CHECKOUT, "card_declined", "change_card")
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState()) as TopUpCheckoutState.Failed
        assertTrue(checkout.recoverable)
        assertEquals(R.string.top_up_card_declined, checkout.messageRes)
        assertEquals(R.string.top_up_use_another_card, checkout.actionRes)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
    }

    @Test fun `core timeout code has checkout expiry guidance`() {
        assertEquals(R.string.top_up_payment_timeout, paymentIssueMessageRes("timeout"))
    }

    @Test fun `restored hidden checkout offers recovery without a new order`() {
        val state = payment(PaymentStage.CHECKOUT, null).let {
            it.copy(record = it.record!!.copy(openedAt = 123L), checkoutVisible = false)
        }
        val checkout = state.toTopUpCheckoutState(CoinPurchaseState()) as TopUpCheckoutState.Failed
        assertTrue(checkout.recoverable)
        assertTrue(corePaymentPresentation(state).canRetry)
        assertFalse(corePaymentPresentation(state).canStartPurchase)
    }

    @Test fun `explicit reopen launches once even with a previous opened timestamp`() {
        val hidden = payment(PaymentStage.CHECKOUT, null).let {
            it.copy(record = it.record!!.copy(openedAt = 123L), checkoutVisible = false)
        }
        val opened = hidden.copy(checkoutVisible = true)
        assertTrue(requestsThirdPartyLaunch(hidden, opened))
        assertFalse(requestsThirdPartyLaunch(opened, opened))
        assertFalse(requestsThirdPartyLaunch(opened, hidden))
        assertFalse(requestsThirdPartyLaunch(hidden, hidden))
    }

    @Test fun `recomposition cannot claim the same browser launch again`() {
        val gate = ThirdPartyLaunchGate()
        assertTrue(gate.claim("session", "order", 1L))
        assertFalse(gate.claim("session", "order", 1L))
        assertTrue(gate.claim("session", "order", 2L))
        assertFalse(gate.claim("session", "order", 1L))
        assertTrue(gate.claim("next-session", "order", 3L))
    }

    private fun payment(stage: PaymentStage, error: String?, action: String? = null) = PaymentViewState(
        epoch = "epoch",
        record = PaymentRecord("key", "user", "pack", "manual", orderId = "order", stage = stage,
            initialized = InitializedPayment("order", "third_party", "payu_web", "webview",
                paymentUrl = "https://checkout.example/order", expiresAt = "2030-01-01T00:00:00Z"),
            error = error, retryAction = action),
    )
}
