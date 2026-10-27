package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.billing.CoinPurchaseState
import yumo.achat.core.billing.CoinPurchaseStatus
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState

class TopUpPaymentCompletionTest {
    @Test fun `same session backend success completes pending official purchase`() {
        assertEquals(
            "epoch-1",
            fulfilledBillingPurchaseEpoch(
                payment = payment(PaymentStage.SUCCESS, "epoch-1"),
                purchase = CoinPurchaseState("epoch-1", CoinPurchaseStatus.PENDING),
            ),
        )
    }

    @Test fun `unfulfilled wrong session and non pending purchases stay locked`() {
        assertNull(
            fulfilledBillingPurchaseEpoch(
                payment = payment(PaymentStage.AWAITING_FULFILLMENT, "epoch-1"),
                purchase = CoinPurchaseState("epoch-1", CoinPurchaseStatus.PENDING),
            ),
        )
        assertNull(
            fulfilledBillingPurchaseEpoch(
                payment = payment(PaymentStage.SUCCESS, "epoch-2"),
                purchase = CoinPurchaseState("epoch-1", CoinPurchaseStatus.PENDING),
            ),
        )
        assertNull(
            fulfilledBillingPurchaseEpoch(
                payment = payment(PaymentStage.SUCCESS, "epoch-1"),
                purchase = CoinPurchaseState("epoch-1", CoinPurchaseStatus.FAILED),
            ),
        )
    }

    private fun payment(stage: PaymentStage, epoch: String) = PaymentViewState(
        epoch = epoch,
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
