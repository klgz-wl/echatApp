package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.billing.BillingProduct
import yumo.achat.core.billing.BillingProductType
import yumo.achat.core.billing.SubscriptionOfferDetails
import yumo.achat.core.billing.PricingPhase

class SubscriptionCatalogPresentationTest {
    @Test fun unusualBackendDurationUsesOnlyAnUnambiguousActualPlayPeriod() {
        val offer = SubscriptionOfferDetails("", "token", "weekly", listOf(PricingPhase("P1W", 0, "$9.99", 9990000, 1)))
        val product = BillingProduct("sku", title = "VIP", description = "", price = "$9.99", priceAmountMicros = 9990000,
            currencyCode = "USD", productType = BillingProductType.SUBS, subscriptionOfferDetails = listOf(offer))
        assertEquals("P1W", subscriptionOfferForProduct(product, 1)?.pricingPhases?.single()?.billingPeriod)
        assertNull(subscriptionOfferForProduct(product.copy(subscriptionOfferDetails = listOf(offer, offer.copy(offerToken = "other"))), 1))
        assertNull(subscriptionOfferForProduct(product, 30))
    }

    @Test fun backendMembershipDaysMapToSupportedPlayBillingPeriods() {
        assertEquals("P1W", subscriptionBillingPeriod(7))
        assertEquals("P1M", subscriptionBillingPeriod(30))
        assertEquals("P1Y", subscriptionBillingPeriod(365))
        assertEquals("P3M", subscriptionBillingPeriod(90))
        assertNull(subscriptionBillingPeriod(0))
        assertNull(subscriptionBillingPeriod(45))
    }
}
