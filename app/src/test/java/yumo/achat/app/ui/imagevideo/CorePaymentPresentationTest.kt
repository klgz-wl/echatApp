package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.payment.PaymentRecord
import yumo.achat.core.payment.PaymentStage
import yumo.achat.core.payment.PaymentViewState

class CorePaymentPresentationTest {
    @Test fun `cancelled order refresh cannot overwrite manually selected amount`() {
        val record = PaymentRecord("old", "user", "coins100", "main", orderId = "old-order",
            stage = PaymentStage.CLOSED, officialCheckoutReleased = true,
            initialized = yumo.achat.core.payment.InitializedPayment("old-order", "official", "google_play", "sdk",
                yumo.achat.core.payment.PaymentSdkParams("sku")))
        assertFalse(shouldAdoptPaymentProduct("old", PaymentViewState("epoch", record), locked = false))
        val next = record.copy(key = "new", stage = PaymentStage.CREATING, officialCheckoutReleased = false)
        assertTrue(shouldAdoptPaymentProduct("old", PaymentViewState("epoch", next), locked = true))
    }
    @Test fun `explicitly cancelled official checkout releases selection and allows new purchase`() {
        val record = PaymentRecord("key", "user", "coins", "main", orderId = "order",
            stage = PaymentStage.CLOSED, officialCheckoutReleased = true,
            initialized = yumo.achat.core.payment.InitializedPayment("order", "official", "google_play", "sdk",
                yumo.achat.core.payment.PaymentSdkParams("sku")))
        val presentation = corePaymentPresentation(PaymentViewState("epoch", record))
        assertFalse(presentation.locksProductSelection)
        assertTrue(presentation.canStartPurchase)
        assertFalse(presentation.canRetry)
    }
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
        listOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT).forEach { stage ->
            assertTrue(corePaymentPresentation(state(stage)).locksProductSelection)
        }
        listOf(PaymentStage.FAILED, PaymentStage.SUCCESS).forEach { stage ->
            assertFalse(corePaymentPresentation(state(stage)).locksProductSelection)
        }
    }

    @Test
    fun `terminal order with order id can start a fresh purchase`() {
        listOf(PaymentStage.FAILED, PaymentStage.ACCESS_DENIED).forEach { stage ->
            val presentation = corePaymentPresentation(state(stage, orderId = "order"))

            assertFalse(presentation.locksProductSelection)
            assertFalse(presentation.canRetry)
            assertTrue(presentation.canStartPurchase)
        }
    }

    @Test
    fun `initialization backend error retries the same purchase`() {
        val presentation = corePaymentPresentation(
            state(
                stage = PaymentStage.INITIALIZING,
                orderId = "order",
                error = "initialize_failed",
            ),
        )

        assertTrue(presentation.locksProductSelection)
        assertTrue(presentation.canRetry)
        assertFalse(presentation.canStartPurchase)
    }

    @Test
    fun `active stage with visible error cannot replace an unconfirmed purchase`() {
        listOf(
            PaymentStage.CREATING,
            PaymentStage.INITIALIZING,
            PaymentStage.OFFICIAL_READY,
            PaymentStage.OFFICIAL_LAUNCHED,
            PaymentStage.CHECKOUT,
            PaymentStage.VERIFYING,
            PaymentStage.AWAITING_FULFILLMENT,
            PaymentStage.UNCERTAIN,
            PaymentStage.OFFICIAL_CONSUMED,
        ).forEach { stage ->
            val presentation = corePaymentPresentation(
                state(
                    stage = stage,
                    orderId = "order",
                    error = "payment_channel_unavailable",
                ),
            )

            assertTrue(presentation.locksProductSelection)
            assertTrue(presentation.canRetry)
            assertFalse(presentation.canStartPurchase)
        }
    }

    @Test
    fun `unknown creation remains locked and offers same request retry`() {
        val presentation = corePaymentPresentation(
            state(
                stage = PaymentStage.UNCERTAIN,
                orderId = null,
                error = "order_unknown",
            ),
        )

        assertTrue(presentation.locksProductSelection)
        assertTrue(presentation.canRetry)
        assertFalse(presentation.canStartPurchase)
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
