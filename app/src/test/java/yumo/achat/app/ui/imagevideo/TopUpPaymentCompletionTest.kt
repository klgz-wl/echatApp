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
    @Test fun `accepted Play purchase waits for confirmation instead of displaying store pending`() {
        val payment = payment(PaymentStage.OFFICIAL_LAUNCHED, "epoch-1").let {
            it.copy(record = it.record!!.copy(initialized = yumo.achat.core.payment.InitializedPayment(
                "order", "official", "google_play", "sdk", yumo.achat.core.payment.PaymentSdkParams("sku"),
            )))
        }
        assertEquals(TopUpCheckoutState.AwaitingPayment(false), payment.toTopUpCheckoutState(
            CoinPurchaseState("epoch-1", CoinPurchaseStatus.AWAITING_FULFILLMENT),
        ))
        assertEquals(TopUpCheckoutState.AwaitingPayment(true), payment.toTopUpCheckoutState(
            CoinPurchaseState("epoch-1", CoinPurchaseStatus.PENDING),
        ))
        assertEquals(TopUpCheckoutState.Succeeded("order"), payment.copy(
            record = payment.record!!.copy(stage = PaymentStage.SUCCESS),
        ).toTopUpCheckoutState(CoinPurchaseState("epoch-1", CoinPurchaseStatus.AWAITING_FULFILLMENT)))
    }

    @Test fun `backend success releases the accepted purchase in the same session only`() {
        val purchase = CoinPurchaseState("epoch-1", CoinPurchaseStatus.AWAITING_FULFILLMENT)
        assertEquals("epoch-1", fulfilledBillingPurchaseEpoch(payment(PaymentStage.SUCCESS, "epoch-1"), purchase))
        assertNull(fulfilledBillingPurchaseEpoch(payment(PaymentStage.SUCCESS, "epoch-2"), purchase))
        assertNull(fulfilledBillingPurchaseEpoch(payment(PaymentStage.AWAITING_FULFILLMENT, "epoch-1"), purchase))
    }

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
