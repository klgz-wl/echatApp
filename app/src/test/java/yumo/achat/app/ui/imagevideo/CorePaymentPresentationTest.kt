package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState

class CorePaymentPresentationTest {
    @Test
    fun `active core payment locks product selection`() {
        val activeStages = listOf(
            PaymentStage.CREATING,
            PaymentStage.INITIALIZING,
            PaymentStage.OFFICIAL_READY,
            PaymentStage.OFFICIAL_LAUNCHED,
            PaymentStage.CHECKOUT,
            PaymentStage.VERIFYING,
            PaymentStage.AWAITING_FULFILLMENT,
            PaymentStage.UNCERTAIN,
            PaymentStage.OFFICIAL_CONSUMED,
        )

        activeStages.forEach { stage ->
            assertTrue(corePaymentPresentation(state(stage)).locksProductSelection)
        }
        listOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT, PaymentStage.FAILED, PaymentStage.SUCCESS).forEach { stage ->
            assertFalse(corePaymentPresentation(state(stage)).locksProductSelection)
        }
    }

    @Test
    fun `terminal order with order id can start a fresh purchase`() {
        listOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT, PaymentStage.FAILED, PaymentStage.ACCESS_DENIED).forEach { stage ->
            val presentation = corePaymentPresentation(state(stage, orderId = "order"))

            assertFalse(presentation.locksProductSelection)
            assertFalse(presentation.canRetry)
            assertTrue(presentation.canStartPurchase)
        }
    }

    @Test
    fun `initialization backend error with order id can start a fresh purchase`() {
        val presentation = corePaymentPresentation(
            state(
                stage = PaymentStage.INITIALIZING,
                orderId = "order",
                error = "initialize_failed",
            ),
        )

        assertFalse(presentation.locksProductSelection)
        assertFalse(presentation.canRetry)
        assertTrue(presentation.canStartPurchase)
    }

    @Test
    fun `order creation failure without order id does not lock product selection`() {
        val presentation = corePaymentPresentation(
            state(
                stage = PaymentStage.UNCERTAIN,
                orderId = null,
                error = "order_unknown",
            ),
        )

        assertFalse(presentation.locksProductSelection)
        assertFalse(presentation.canRetry)
        assertTrue(presentation.canStartPurchase)
    }

    private fun state(
        stage: PaymentStage,
        orderId: String? = "order",
        error: String? = null,
    ) = PaymentViewState(
        epoch = "epoch",
        record = PaymentRecord(
            key = "key",
            userId = "user",
            productId = "pack",
            source = "main",
            orderId = orderId,
            stage = stage,
            error = error,
        ),
    )
}
