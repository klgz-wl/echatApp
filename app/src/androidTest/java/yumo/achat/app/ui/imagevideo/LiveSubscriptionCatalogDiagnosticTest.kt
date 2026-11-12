package yumo.achat.app.ui.imagevideo

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import yumo.achat.core.billing.BillingManager
import yumo.achat.core.billing.BillingProductType

class LiveSubscriptionCatalogDiagnosticTest {
    @Test fun inspectCurrentAccountSubscriptionCatalogWithoutPurchasing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Run only when explicitly requested; normal device suites do not require live login/network.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveSubscriptionDiagnostic") == "true")
        val context = instrumentation.targetContext
        val catalog = createAchatRepository(context).membershipCatalog()
        fun report(line: String) { instrumentation.sendStatus(0, Bundle().apply { putString("subscription_diagnostic", line) }) }
        report("user hasMadeFirstPurchase=${catalog.userInfo?.hasMadeFirstPurchase}")
        catalog.products.forEach { report("catalog id=${it.id} sku=${it.officialProductId} days=${it.value} subscription=${it.isSubscription} price=${it.price} original=${it.originalPrice} currency=${it.currency} firstBuy=${it.isFirstBuyPromotion} firstPrice=${it.firstBuyPrice}") }
        val manager = BillingManager(corePaymentConfiguration().billing)
        manager.initialize(context)
        val connected = manager.connect()
        report("connect code=${connected.responseCode}")
        val result = manager.queryProducts(catalog.products.map { it.officialProductId }.distinct(), BillingProductType.SUBS)
        report("query code=${result.responseCode} count=${result.data?.size}")
        result.data.orEmpty().forEach { product ->
            report("play sku=${product.id} offers=" + product.subscriptionOfferDetails.orEmpty().joinToString { offer ->
                "base=${offer.basePlanId} intro=${offer.offerId.isNotBlank()} phases=" + offer.pricingPhases.joinToString { "${it.billingPeriod}:${it.recurrenceMode}:${it.priceFormatted}" }
            })
        }
        manager.disconnect()
    }
}
