package yumo.achat.app.ui.imagevideo

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

internal sealed interface GooglePurchaseResult {
    data class Purchased(val recoveredOrderId: String? = null) : GooglePurchaseResult
    data class Pending(val recoveredOrderId: String? = null) : GooglePurchaseResult
    data object Cancelled : GooglePurchaseResult
    data class Error(val message: String) : GooglePurchaseResult
}

internal data class RecoveredGooglePurchase(val orderId: String, val pending: Boolean)

internal class GooglePlayBillingManager(context: Context) : PurchasesUpdatedListener {
    private var callback: ((GooglePurchaseResult) -> Unit)? = null
    private var pendingRoute: TopUpPurchaseState.OfficialReady? = null
    private var connecting = false
    private val connectionWaiters = mutableListOf<() -> Unit>()
    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    fun launch(
        activity: Activity,
        route: TopUpPurchaseState.OfficialReady,
        callback: (GooglePurchaseResult) -> Unit,
    ) {
        this.callback = callback
        pendingRoute = route
        connect {
            restoreOrLaunch(activity, route, callback)
        }
    }

    fun restoreActivePurchases(callback: (List<RecoveredGooglePurchase>) -> Unit) {
        connect {
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    callback(emptyList())
                    return@queryPurchasesAsync
                }
                callback(purchases.mapNotNull { purchase ->
                    val orderId = purchase.accountIdentifiers?.obfuscatedProfileId.orEmpty()
                    if (orderId.isBlank()) null else RecoveredGooglePurchase(
                        orderId = orderId,
                        pending = purchase.purchaseState != Purchase.PurchaseState.PURCHASED,
                    )
                })
            }
        }
    }

    private fun restoreOrLaunch(
        activity: Activity,
        route: TopUpPurchaseState.OfficialReady,
        callback: (GooglePurchaseResult) -> Unit,
    ) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            val existing = purchases.firstOrNull { purchase -> matchesRoute(purchase, route) }
            if (result.responseCode == BillingClient.BillingResponseCode.OK && existing != null) {
                val recoveredOrderId = existing.accountIdentifiers?.obfuscatedProfileId
                deliver(if (existing.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    GooglePurchaseResult.Purchased(recoveredOrderId)
                } else GooglePurchaseResult.Pending(recoveredOrderId))
                return@queryPurchasesAsync
            }
            queryProduct(route.sdkProductId) { query ->
                query.onSuccess { details -> launchFlow(activity, route, details) }
                    .onFailure { deliver(GooglePurchaseResult.Error(it.message ?: "Product unavailable")) }
            }
        }
    }

    private fun connect(onConnected: () -> Unit) {
        if (billingClient.isReady) {
            onConnected()
            return
        }
        connectionWaiters += onConnected
        if (connecting) return
        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val waiters = connectionWaiters.toList()
                connectionWaiters.clear()
                waiters.forEach { it() }
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    deliver(GooglePurchaseResult.Error(result.debugMessage))
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun queryProduct(productId: String, result: (Result<ProductDetails>) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            val details = queryResult.productDetailsList.firstOrNull { it.productId == productId }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && details != null) {
                result(Result.success(details))
            } else {
                result(Result.failure(IllegalStateException(billingResult.debugMessage.ifBlank { "Product unavailable" })))
            }
        }
    }

    private fun launchFlow(activity: Activity, route: TopUpPurchaseState.OfficialReady, details: ProductDetails) {
        val offerToken = details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { if (!offerToken.isNullOrBlank()) setOfferToken(offerToken) }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .apply {
                if (route.obfuscatedAccountId.isNotBlank()) setObfuscatedAccountId(route.obfuscatedAccountId)
                if (route.obfuscatedProfileId.isNotBlank()) setObfuscatedProfileId(route.obfuscatedProfileId)
            }
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            deliver(GooglePurchaseResult.Error(result.debugMessage))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val route = pendingRoute
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = if (route == null) null else purchases?.firstOrNull { matchesRoute(it, route) }
                deliver(
                    when (purchase?.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> GooglePurchaseResult.Purchased()
                        Purchase.PurchaseState.PENDING -> GooglePurchaseResult.Pending()
                        else -> GooglePurchaseResult.Error("Purchase state unavailable")
                    },
                )
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> deliver(GooglePurchaseResult.Cancelled)
            else -> deliver(GooglePurchaseResult.Error(result.debugMessage))
        }
    }

    private fun deliver(result: GooglePurchaseResult) {
        val target = callback
        if (result !is GooglePurchaseResult.Pending) {
            callback = null
            pendingRoute = null
        }
        target?.invoke(result)
    }

    private fun matchesRoute(purchase: Purchase, route: TopUpPurchaseState.OfficialReady): Boolean {
        if (route.sdkProductId !in purchase.products) return false
        val expectedProfile = route.obfuscatedProfileId
        val actualProfile = purchase.accountIdentifiers?.obfuscatedProfileId.orEmpty()
        return expectedProfile.isBlank() || expectedProfile == actualProfile
    }

    companion object {
        @Volatile private var instance: GooglePlayBillingManager? = null

        fun get(context: Context): GooglePlayBillingManager = instance ?: synchronized(this) {
            instance ?: GooglePlayBillingManager(context.applicationContext).also { instance = it }
        }
    }
}
