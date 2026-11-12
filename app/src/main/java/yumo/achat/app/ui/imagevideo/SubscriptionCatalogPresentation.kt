package yumo.achat.app.ui.imagevideo

import yumo.achat.core.backend.StoreProduct

internal fun subscriptionProducts(products: List<StoreProduct>): List<StoreProduct> = products
    .filter { it.type == "membership" && it.isSubscription && it.value > 0 && it.price.signum() >= 0 && it.currency.isNotBlank() }
    .distinctBy { it.id }
    .sortedWith(compareByDescending<StoreProduct> { it.value }.thenBy { it.sortOrder })

internal fun subscriptionBillingPeriod(days: Int): String? = when (days) {
    7 -> "P1W"
    30, 31 -> "P1M"
    90, 91, 92 -> "P3M"
    180, 181, 182, 183 -> "P6M"
    365, 366 -> "P1Y"
    else -> null
}

internal fun subscriptionOfferForProduct(
    product: yumo.achat.core.billing.BillingProduct, days: Int,
): yumo.achat.core.billing.SubscriptionOfferDetails? {
    val expected = subscriptionBillingPeriod(days)
    if (expected != null) return yumo.achat.core.billing.selectSubscriptionOffer(product, expected)
    val periods = product.subscriptionOfferDetails.orEmpty().flatMap { it.pricingPhases }.map { it.billingPeriod }.distinct()
    return periods.mapNotNull { yumo.achat.core.billing.selectSubscriptionOffer(product, it) }.singleOrNull()
}
