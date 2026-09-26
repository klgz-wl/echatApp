package com.zorv.core.billing

data class BillingProduct(
    val id: String,
    val name: String = "",
    val title: String,
    val description: String,
    val price: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
    val productType: BillingProductType,
    val offerToken: String? = null,
    val subscriptionOfferDetails: List<SubscriptionOfferDetails>? = null
)

data class SubscriptionOfferDetails(
    val offerId: String,
    val offerToken: String,
    val basePlanId: String,
    val pricingPhases: List<PricingPhase>
)

data class PricingPhase(
    val billingPeriod: String,
    val billingCycleCount: Int,
    val priceFormatted: String,
    val priceAmountMicros: Long,
    val recurrenceMode: Int
)
