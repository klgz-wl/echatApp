package yumo.achat.app.ui.imagevideo

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.CancellationException
import yumo.achat.app.R
import yumo.achat.app.ui.error.diagnosticMessage
import yumo.achat.core.backend.AchatRepository
import yumo.achat.core.backend.StoreProduct
import yumo.achat.core.billing.SubscriptionPurchaseStage

@Composable
internal fun SubscriptionRoute(repository: AchatRepository, trigger: String, isVip: Boolean?, onBack: () -> Unit, templateEntry: Boolean = false) {
    val viewModel: SubscriptionViewModel = hiltViewModel()
    val payment by viewModel.purchases.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var products by remember { mutableStateOf<List<StoreProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var planToast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { planToast?.cancel() } }
    BackHandler(onBack = onBack)
    DisposableEffect(lifecycle, viewModel) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME) {
                if (paused) viewModel.purchases.onCheckoutReturned()
                paused = false
                viewModel.restore()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository, revision) {
        loading = true
        error = null
        try {
            products = subscriptionProducts(repository.membershipCatalog().products)
            loading = false
            if (products.isNotEmpty()) {
                try {
                    viewModel.purchases.queryProducts(products.map { it.officialProductId })
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep authoritative API prices visible when Play is unavailable. */ }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            error = context.diagnosticMessage(resources.getString(R.string.subscription_catalog_error), "E-501")
        } finally { loading = false }
    }
    val busy = payment.stage in setOf(SubscriptionPurchaseStage.Loading, SubscriptionPurchaseStage.Purchasing,
        SubscriptionPurchaseStage.Pending, SubscriptionPurchaseStage.AwaitingFulfillment, SubscriptionPurchaseStage.Closing)
    val plans = products.map { product ->
        val playProduct = payment.products.firstOrNull { it.id == product.officialProductId }
        val quote = playProduct?.let { subscriptionOfferForProduct(it, product.value)?.pricingPhases?.singleOrNull() }
        val period = quote?.billingPeriod ?: subscriptionBillingPeriod(product.value)
        val title = resources.getString(when (period) {
            "P1W" -> R.string.subscription_weekly
            "P1M" -> R.string.subscription_monthly
            "P1Y" -> R.string.subscription_yearly
            else -> R.string.subscription_days
        }, product.value)
        SubscriptionPlan(product.id, title,
            quote?.priceFormatted ?: formatStoreMoney(product.price, product.currency, locale),
            resources.getString(when (period) {
                "P1W" -> R.string.subscription_renews_weekly
                "P1M" -> R.string.subscription_renews_monthly
                "P3M" -> R.string.subscription_renews_quarterly
                "P6M" -> R.string.subscription_renews_half_yearly
                "P1Y" -> R.string.subscription_renews_yearly
                else -> R.string.subscription_duration
            }, product.value))
    }
    val message = when (payment.stage) {
        SubscriptionPurchaseStage.Success -> resources.getString(R.string.subscription_purchase_success)
        SubscriptionPurchaseStage.Pending -> resources.getString(R.string.subscription_purchase_pending)
        SubscriptionPurchaseStage.AwaitingFulfillment -> resources.getString(R.string.subscription_purchase_verifying)
        SubscriptionPurchaseStage.Error -> when {
            payment.error == "subscription_product_unavailable" -> resources.getString(R.string.subscription_account_product_unavailable)
            payment.error == "subscription_order_creation_unknown" -> resources.getString(R.string.subscription_order_creation_unknown)
            payment.error == "subscription_already_owned" -> resources.getString(R.string.subscription_already_owned)
            payment.error == "subscription_products_unavailable" -> resources.getString(R.string.subscription_plan_unavailable)
            payment.canRetrySamePlan && !payment.canChangePlan -> resources.getString(R.string.subscription_previous_checkout_unconfirmed)
            payment.hasUnresolvedOrder -> resources.getString(R.string.subscription_check_existing)
            else -> context.diagnosticMessage(resources.getString(R.string.subscription_purchase_retry_error), "E-602")
        }
        SubscriptionPurchaseStage.Cancelled, SubscriptionPurchaseStage.Closing -> resources.getString(R.string.subscription_purchase_cancelled)
        else -> null
    }
    fun purchasePlan(id: String) {
        val product = products.firstOrNull { it.id == id }
        val playProduct = product?.let { p -> payment.products.firstOrNull { it.id == p.officialProductId } }
        val offer = if (product != null && playProduct != null) subscriptionOfferForProduct(playProduct, product.value) else null
        val activity = context as? Activity
        if (product != null && offer != null && activity != null) {
            viewModel.buy(activity, product.id, product.officialProductId, offer.pricingPhases.single().billingPeriod, trigger, isVip)
        } else {
            val messageRes = if (product == null || offer == null) R.string.subscription_play_plan_unavailable
                else R.string.subscription_purchase_retry_error
            planToast?.cancel()
            planToast = Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    SubscriptionScreen(plans, loading, error, onRetry = { revision++ }, onBack = onBack,
        purchaseBusy = busy,
        planSelectionEnabled = payment.stage != SubscriptionPurchaseStage.Purchasing,
        purchaseMessage = message,
        checkoutState = payment,
        templateEntry = templateEntry,
        onPlanSelected = { planToast?.cancel(); planToast = null },
        onCheckPurchase = viewModel::restore,
        onResumePurchase = { payment.productId?.let(::purchasePlan) },
        onSubscribe = ::purchasePlan)
}
