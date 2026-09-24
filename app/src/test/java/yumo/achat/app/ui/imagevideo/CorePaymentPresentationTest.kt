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
            PaymentStage.CLOSED,
            PaymentStage.TIMED_OUT,
            PaymentStage.OFFICIAL_CONSUMED,
        )

        activeStages.forEach { stage ->
            assertTrue(corePaymentPresentation(state(stage)).locksProductSelection)
        }
        listOf(PaymentStage.FAILED, PaymentStage.SUCCESS).forEach { stage ->
            assertFalse(corePaymentPresentation(state(stage)).locksProductSelection)
        }
    }

    private fun state(stage: PaymentStage) = PaymentViewState(
        epoch = "epoch",
        record = PaymentRecord(
            key = "key",
            userId = "user",
            productId = "pack",
            source = "main",
            orderId = "order",
            stage = stage,
        ),
    )
}
