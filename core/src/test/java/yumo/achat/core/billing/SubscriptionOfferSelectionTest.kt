package yumo.achat.core.billing

import org.junit.Assert.*
import org.junit.Test

class SubscriptionOfferSelectionTest {
    private fun phase(period: String = "P1M", recurrence: Int = 1, micros: Long = 9990000) =
        PricingPhase(period, 0, "$9.99", micros, recurrence)
    private fun offer(phases: List<PricingPhase> = listOf(phase()), id: String = "", token: String = "token") =
        SubscriptionOfferDetails(id, token, "monthly", phases)
    private fun product(offers: List<SubscriptionOfferDetails>) = BillingProduct(
        "sku", title = "VIP", description = "", price = "$9.99", priceAmountMicros = 9990000,
        currencyCode = "USD", productType = BillingProductType.SUBS, subscriptionOfferDetails = offers,
    )
    @Test fun matchingRecurringBasePlanIsSelected() {
        val expected = offer()
        assertEquals(expected, selectSubscriptionOffer(product(listOf(expected)), "P1M"))
    }
    @Test fun wrongPeriodDoesNotFallBackToFirstOffer() {
        assertNull(selectSubscriptionOffer(product(listOf(offer())), "P1Y"))
    }
    @Test fun promotionalPhasesAreNotPresentedAsRecurringPrice() {
        assertNull(selectSubscriptionOffer(product(listOf(offer(listOf(phase(micros = 0), phase())))), "P1M"))
    }
    @Test fun ambiguityIsRejected() {
        assertNull(selectSubscriptionOffer(product(listOf(offer(), offer(token = "other"))), "P1M"))
    }
    @Test fun prepaidAndIntroOffersAreRejected() {
        assertNull(selectSubscriptionOffer(product(listOf(offer(listOf(phase(recurrence = 3))))), "P1M"))
        assertNull(selectSubscriptionOffer(product(listOf(offer(id = "intro"))), "P1M"))
    }
    @Test fun missingTokenIsRejected() {
        assertNull(selectSubscriptionOffer(product(listOf(offer(token = ""))), "P1M"))
    }
}
