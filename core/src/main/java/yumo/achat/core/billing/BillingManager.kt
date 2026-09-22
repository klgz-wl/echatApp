package yumo.achat.core.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult as GoogleBillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class BillingManager @Inject constructor(private val config: BillingConfiguration) :
    PurchasesUpdatedListener,
    BillingClientStateListener {

    private var billingClient: BillingClient? = null

    private val _connectionState = MutableStateFlow<BillingConnectionState>(
        BillingConnectionState.DISCONNECTED
    )
    val connectionState: StateFlow<BillingConnectionState> = _connectionState

    private val purchaseRequestCoordinator = PurchaseRequestCoordinator()
    private val pendingPurchaseRegistry = PendingPurchaseRegistry()
    private val pendingPurchasePersistenceLock = Any()
    private var pendingPurchasePreferences: android.content.SharedPreferences? = null
    private val _recoveredPurchases = MutableSharedFlow<BillingPurchase>(extraBufferCapacity = 8)
    internal val recoveredPurchases: SharedFlow<BillingPurchase> = _recoveredPurchases.asSharedFlow()

    private val productDetailsCache = mutableMapOf<String, ProductDetails>()


    fun initialize(context: Context) {
        synchronized(pendingPurchasePersistenceLock) {
            if (pendingPurchasePreferences == null) {
                pendingPurchasePreferences = context.getSharedPreferences(config.storageName, Context.MODE_PRIVATE)
                pendingPurchaseRegistry.restore(readPersistedPendingPurchases())
            }
        }
        if (billingClient != null) {
            Timber.d("Billing client already initialized")
            return
        }

        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()

        Timber.d("Billing client initialized")
    }


    suspend fun connect(): BillingResult<Unit> = suspendCancellableCoroutine { continuation ->
        if (_connectionState.value == BillingConnectionState.CONNECTED) {
            if (continuation.isActive) continuation.resume(BillingResult.success(Unit))
            return@suspendCancellableCoroutine
        }
        val client = billingClient
        if (client == null) {
            Timber.e("Billing client not initialized")
            if (continuation.isActive) continuation.resume(
                BillingResult.error(
                    BillingResponseCode.DEVELOPER_ERROR,
                    "Billing client not initialized"
                )
            )
            return@suspendCancellableCoroutine
        }

        _connectionState.value = BillingConnectionState.CONNECTING
        Timber.d("[Billing] Connect started")

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: GoogleBillingResult) {
                val responseCode = billingResult.responseCode
                if (responseCode == BillingClient.BillingResponseCode.OK) {
                    _connectionState.value = BillingConnectionState.CONNECTED
                    Timber.d("[Billing] Connect success")
                    if (continuation.isActive) continuation.resume(BillingResult.success(Unit))
                } else {
                    _connectionState.value = BillingConnectionState.FAILED
                    Timber.e("[Billing] Connect failed: ${BillingResponseCode.describe(responseCode)}")
                    if (continuation.isActive) continuation.resume(
                        BillingResult.error(
                            responseCode,
                            "Billing connection failed: ${billingResult.debugMessage}",
                            billingResult.debugMessage
                        )
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                _connectionState.value = BillingConnectionState.DISCONNECTED
                Timber.d("Billing service disconnected")
            }
        })
    }


    suspend fun queryProducts(
        productIds: List<String>,
        productType: BillingProductType
    ): BillingResult<List<BillingProduct>> = suspendCancellableCoroutine { continuation ->
        val client = billingClient
        if (client == null) {
            if (continuation.isActive) continuation.resume(
                BillingResult.error(
                    BillingResponseCode.DEVELOPER_ERROR,
                    "Billing client not initialized"
                )
            )
            return@suspendCancellableCoroutine
        }

        if (_connectionState.value != BillingConnectionState.CONNECTED) {
            if (continuation.isActive) continuation.resume(
                BillingResult.error(
                    BillingResponseCode.SERVICE_UNAVAILABLE,
                    "Billing client not connected"
                )
            )
            return@suspendCancellableCoroutine
        }

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType.toBillingType())
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Timber.d("[Billing] QueryProducts started")

        client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            val responseCode = billingResult.responseCode
            val productDetailsList = queryProductDetailsResult.productDetailsList
            if (responseCode == BillingClient.BillingResponseCode.OK) {
                productDetailsList.forEach { details ->
                    productDetailsCache[details.productId] = details
                }
                val products = productDetailsList.map { it.toDomainModel(productType) }
                Timber.d("[Billing] QueryProducts success: ${products.size} found, cacheSize=${productDetailsCache.size}")
                if (continuation.isActive) continuation.resume(BillingResult.success(products))
            } else {
                Timber.e("[Billing] QueryProducts failed: ${BillingResponseCode.describe(responseCode)}")
                if (continuation.isActive) continuation.resume(
                    BillingResult.error(
                        responseCode,
                        "Query products failed: ${billingResult.debugMessage}",
                        billingResult.debugMessage
                    )
                )
            }
        }
    }


    fun getProductDetails(productId: String): ProductDetails? = productDetailsCache[productId]


    internal fun launchPurchaseFlow(
        activity: Activity,
        productId: String,
        productType: BillingProductType,
        offerToken: String? = null,
        userId: String? = null,
        orderId: String? = null
    ): PurchaseRequest {
        Timber.d("[Billing] LaunchPurchase started")

        val request = purchaseRequestCoordinator.start(productId, productType, orderId, userId)
        if (!purchaseRequestCoordinator.isActive(request)) {
            Timber.e("[Billing] LaunchPurchase rejected: another purchase is already in progress")
            return request
        }

        val client = billingClient ?: run {
            Timber.e("[Billing] LaunchPurchase failed: BillingClient not initialized")
            purchaseRequestCoordinator.complete(
                request,
                BillingResult.error(
                    BillingResponseCode.DEVELOPER_ERROR,
                    "Billing client not initialized",
                ),
            )
            return request
        }

        val productDetails = productDetailsCache[productId] ?: run {
            Timber.e("[Billing] LaunchPurchase failed: product not in cache, cachedProducts=${productDetailsCache.size}")
            purchaseRequestCoordinator.complete(
                request,
                BillingResult.error(
                    BillingResponseCode.ITEM_UNAVAILABLE,
                    "Product not found: $productId"
                )
            )
            return request
        }

        Timber.d("[Billing] ProductDetails resolved: hasOneTimeOffer=${productDetails.oneTimePurchaseOfferDetails != null}")

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        if (productType == BillingProductType.SUBS) {
            val subscriptionOfferDetails = productDetails.subscriptionOfferDetails
            if (subscriptionOfferDetails.isNullOrEmpty()) {
                Timber.e("No subscription offer details found")
                purchaseRequestCoordinator.complete(
                    request,
                    BillingResult.error(
                        BillingResponseCode.DEVELOPER_ERROR,
                        "No subscription offer details found"
                    )
                )
                return request
            }

            val token = offerToken ?: subscriptionOfferDetails.first().offerToken
            productParamsBuilder.setOfferToken(token)
        }

        val paramsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))

        if (!userId.isNullOrBlank()) {
            paramsBuilder.setObfuscatedAccountId(userId)
        }

        if (!orderId.isNullOrBlank()) {
            paramsBuilder.setObfuscatedProfileId(orderId)
        }

        val params = paramsBuilder.build()

        val billingResult = client.launchBillingFlow(activity, params)

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            registerPendingPurchase(
                PendingPurchaseRecord(
                    productId = productId,
                    orderId = orderId,
                    productType = productType,
                    purchaseToken = null,
                    userId = userId,
                ),
            )
            Timber.d("[Billing] launchBillingFlow OK, waiting for user action...")
        } else {
            Timber.e("[Billing] launchBillingFlow failed: ${BillingResponseCode.describe(billingResult.responseCode)}")
            purchaseRequestCoordinator.complete(
                request,
                BillingResult.error(
                    billingResult.responseCode,
                    "Launch billing flow failed: ${billingResult.debugMessage}",
                    billingResult.debugMessage,
                ),
            )
        }
        return request
    }


    internal suspend fun waitForPurchaseResult(request: PurchaseRequest): BillingResult<BillingPurchase> {
        return request.await()
    }


    suspend fun acknowledgePurchase(purchaseToken: String): BillingResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val client = billingClient
            if (client == null) {
                if (continuation.isActive) continuation.resume(
                    BillingResult.error(
                        BillingResponseCode.DEVELOPER_ERROR,
                        "Billing client not initialized"
                    )
                )
                return@suspendCancellableCoroutine
            }

            val params = com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()

            client.acknowledgePurchase(params) { billingResult: GoogleBillingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Timber.d("Purchase acknowledged successfully")
                    if (continuation.isActive) continuation.resume(BillingResult.success(Unit))
                } else {
                    val errorMessage = "Acknowledge purchase failed: ${billingResult.debugMessage}"
                    Timber.e("Acknowledge purchase failed: ${BillingResponseCode.describe(billingResult.responseCode)}")
                    if (continuation.isActive) continuation.resume(
                        BillingResult.error(
                            billingResult.responseCode,
                            errorMessage,
                            billingResult.debugMessage
                        )
                    )
                }
            }
        }


    suspend fun consumePurchase(purchaseToken: String): BillingResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val client = billingClient
            if (client == null) {
                if (continuation.isActive) continuation.resume(
                    BillingResult.error(
                        BillingResponseCode.DEVELOPER_ERROR,
                        "Billing client not initialized"
                    )
                )
                return@suspendCancellableCoroutine
            }

            val params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()

            client.consumeAsync(params) { billingResult, _ ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Timber.d("Purchase consumed successfully")
                    if (continuation.isActive) continuation.resume(BillingResult.success(Unit))
                } else {
                    val errorMessage = "Consume purchase failed: ${billingResult.debugMessage}"
                    Timber.e("Consume purchase failed: ${BillingResponseCode.describe(billingResult.responseCode)}")
                    if (continuation.isActive) continuation.resume(
                        BillingResult.error(
                            billingResult.responseCode,
                            errorMessage,
                            billingResult.debugMessage
                        )
                    )
                }
            }
        }


    suspend fun queryPurchases(productType: BillingProductType): BillingResult<List<BillingPurchase>> =
        suspendCancellableCoroutine { continuation ->
            val client = billingClient
            if (client == null) {
                if (continuation.isActive) continuation.resume(
                    BillingResult.error(
                        BillingResponseCode.DEVELOPER_ERROR,
                        "Billing client not initialized"
                    )
                )
                return@suspendCancellableCoroutine
            }

            val params = QueryPurchasesParams.newBuilder()
                .setProductType(productType.toBillingType())
                .build()

            client.queryPurchasesAsync(params) { billingResult, purchaseList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val purchases = purchaseList.map { it.toDomainModel(productType) }
                    purchaseList.forEach { purchase ->
                        emitRecoveredPurchaseIfTracked(purchase, productType)
                    }
                    Timber.d("Query purchases successful: ${purchases.size} purchases found")
                    if (continuation.isActive) continuation.resume(BillingResult.success(purchases))
                } else {
                    val errorMessage = "Query purchases failed: ${billingResult.debugMessage}"
                    Timber.e("Query purchases failed: ${BillingResponseCode.describe(billingResult.responseCode)}")
                    if (continuation.isActive) continuation.resume(
                        BillingResult.error(
                            billingResult.responseCode,
                            errorMessage,
                            billingResult.debugMessage
                        )
                    )
                }
            }
        }


    fun disconnect() {
        purchaseRequestCoordinator.completeActive(
            BillingResult.error(
                BillingResponseCode.SERVICE_DISCONNECTED,
                "Billing service disconnected",
            ),
        )
        billingClient?.endConnection()
        billingClient = null
        productDetailsCache.clear()
        _connectionState.value = BillingConnectionState.DISCONNECTED
        Timber.d("Billing disconnected")
    }

    override fun onPurchasesUpdated(billingResult: GoogleBillingResult, purchases: MutableList<Purchase>?) {
        val responseCode = billingResult.responseCode
        Timber.d("[Billing] onPurchasesUpdated: ${BillingResponseCode.describe(responseCode)}, purchases=${purchases?.size ?: 0}")

        if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                val request = purchaseRequestCoordinator.findMatching(
                    productIds = purchase.products,
                    orderId = purchase.accountIdentifiers?.obfuscatedProfileId,
                )
                val pendingRecord = pendingPurchaseRegistry.findMatching(
                    productIds = purchase.products,
                    orderId = purchase.accountIdentifiers?.obfuscatedProfileId,
                    purchaseToken = purchase.purchaseToken,
                )
                if (request == null && pendingRecord == null) {
                    Timber.d("[Billing] Ignoring purchase update without matching transaction")
                    continue
                }
                val productType = request?.productType ?: pendingRecord!!.productType
                val productId = request?.productId ?: pendingRecord!!.productId
                val domainPurchase = purchase.toDomainModel(productType).copy(productId = productId)

                Timber.d(
                    "[Billing] Purchase detail: productCount=${purchase.products.size}, " +
                        "state=${purchase.purchaseState}, acknowledged=${purchase.isAcknowledged}"
                )

                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        if (request != null) {
                            registerPendingPurchase(
                                PendingPurchaseRecord(
                                    productId = productId,
                                    orderId = request.orderId ?: pendingRecord?.orderId,
                                    productType = productType,
                                    purchaseToken = purchase.purchaseToken,
                                    userId = pendingRecord?.userId ?: request.userId,
                                ),
                            )
                            purchaseRequestCoordinator.complete(request, BillingResult.success(domainPurchase))
                        }
                        _recoveredPurchases.tryEmit(domainPurchase)
                        Timber.d("[Billing] Purchase completed: productCount=${purchase.products.size}")
                    }
                    Purchase.PurchaseState.PENDING -> {
                        registerPendingPurchase(
                            PendingPurchaseRecord(
                                productId = productId,
                                orderId = request?.orderId ?: pendingRecord?.orderId,
                                productType = productType,
                                purchaseToken = purchase.purchaseToken,
                                userId = pendingRecord?.userId ?: request?.userId,
                            ),
                        )
                        if (request != null) {
                            purchaseRequestCoordinator.complete(request, BillingResult.success(domainPurchase))
                        }
                        Timber.w("[Billing] Purchase pending; transaction persisted for recovery")
                    }
                    Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                        pendingRecord?.let(::removePendingPurchase)
                        if (request != null) {
                            purchaseRequestCoordinator.complete(
                                request,
                                BillingResult.error(
                                    BillingResponseCode.ERROR,
                                    "Purchase state unspecified"
                                )
                            )
                        }
                        Timber.e("[Billing] Purchase state unspecified")
                    }
                    else -> {
                        pendingRecord?.let(::removePendingPurchase)
                        if (request != null) {
                            purchaseRequestCoordinator.complete(
                                request,
                                BillingResult.error(
                                    BillingResponseCode.ERROR,
                                    "Unknown purchase state: ${purchase.purchaseState}"
                                )
                            )
                        }
                        Timber.e("[Billing] Purchase unknown state: state=${purchase.purchaseState}")
                    }
                }
            }
        } else if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            removeActivePendingPurchase()
            purchaseRequestCoordinator.completeActive(
                BillingResult.error(
                    BillingResponseCode.CANCELLED,
                    "Purchase cancelled by user"
                )
            )
            Timber.d("[Billing] Purchase cancelled by user")
        } else {
            removeActivePendingPurchase()
            purchaseRequestCoordinator.completeActive(
                BillingResult.error(
                    responseCode,
                    "Purchase failed",
                    billingResult.debugMessage
                )
            )
            Timber.e("[Billing] Purchase failed: ${BillingResponseCode.describe(responseCode)}")
        }
    }

    override fun onBillingSetupFinished(billingResult: GoogleBillingResult) {
        val responseCode = billingResult.responseCode
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            _connectionState.value = BillingConnectionState.CONNECTED
            Timber.d("Billing setup finished successfully")
        } else {
            _connectionState.value = BillingConnectionState.FAILED
            Timber.e("Billing setup failed: ${BillingResponseCode.describe(responseCode)}")
        }
    }

    override fun onBillingServiceDisconnected() {
        _connectionState.value = BillingConnectionState.DISCONNECTED
        Timber.d("Billing service disconnected")
    }

    internal fun markRecoveredPurchaseHandled(purchaseToken: String) {
        synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.findMatching(emptyList(), null, purchaseToken)?.let {
                pendingPurchaseRegistry.remove(it)
                persistPendingPurchasesLocked()
            }
        }
    }

    internal fun trackedPurchaseRecord(purchaseToken: String): PendingPurchaseRecord? =
        synchronized(pendingPurchasePersistenceLock) { pendingPurchaseRegistry.findMatching(emptyList(), null, purchaseToken) }

    internal fun trackedPurchaseOwnerUserId(purchaseToken: String): String? =
        synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.findMatching(emptyList(), null, purchaseToken)?.userId
        }

    internal fun isTrackedPurchase(purchaseToken: String): Boolean =
        synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.findMatching(emptyList(), null, purchaseToken) != null
        }

    private fun removeActivePendingPurchase() {
        synchronized(pendingPurchasePersistenceLock) {
            purchaseRequestCoordinator.activeRequest()?.let { request ->
                pendingPurchaseRegistry.findMatching(
                    productIds = listOf(request.productId),
                    orderId = request.orderId,
                    purchaseToken = null,
                )?.let {
                    pendingPurchaseRegistry.remove(it)
                    persistPendingPurchasesLocked()
                }
            }
        }
    }

    private fun emitRecoveredPurchaseIfTracked(purchase: Purchase, queriedProductType: BillingProductType) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val tracked = synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.attachPurchaseToken(
                productIds = purchase.products,
                orderId = purchase.accountIdentifiers?.obfuscatedProfileId,
                purchaseToken = purchase.purchaseToken,
            )?.also { persistPendingPurchasesLocked() }
        } ?: return
        _recoveredPurchases.tryEmit(
            purchase.toDomainModel(queriedProductType).copy(
                productId = tracked.productId,
                productType = tracked.productType,
            ),
        )
    }

    private fun registerPendingPurchase(record: PendingPurchaseRecord) {
        synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.register(record)
            persistPendingPurchasesLocked()
        }
    }

    private fun removePendingPurchase(record: PendingPurchaseRecord) {
        synchronized(pendingPurchasePersistenceLock) {
            pendingPurchaseRegistry.remove(record)
            persistPendingPurchasesLocked()
        }
    }

    private fun readPersistedPendingPurchases(): List<PendingPurchaseRecord> =
        pendingPurchasePreferences?.getStringSet(PENDING_PURCHASES_KEY, emptySet()).orEmpty().mapNotNull { raw ->
            runCatching {
                val json = JSONObject(raw)
                PendingPurchaseRecord(
                    productId = json.getString("productId"),
                    orderId = json.optString("orderId").takeIf(String::isNotBlank),
                    productType = BillingProductType.valueOf(json.getString("productType")),
                    purchaseToken = json.optString("purchaseToken").takeIf(String::isNotBlank),
                    userId = json.optString("userId").takeIf(String::isNotBlank),
                )
            }.onFailure { Timber.e("恢复待处理购买记录失败") }.getOrNull()
        }

    private fun persistPendingPurchasesLocked() {
        val encoded = pendingPurchaseRegistry.snapshot().mapTo(mutableSetOf()) { record ->
            JSONObject()
                .put("productId", record.productId)
                .put("orderId", record.orderId.orEmpty())
                .put("productType", record.productType.name)
                .put("purchaseToken", record.purchaseToken.orEmpty())
                .put("userId", record.userId.orEmpty())
                .toString()
        }
        pendingPurchasePreferences?.edit()?.putStringSet(PENDING_PURCHASES_KEY, encoded)?.commit()
    }

    private companion object {
        const val PENDING_PURCHASES_KEY = "transactions"
    }
}

private fun ProductDetails.toDomainModel(productType: BillingProductType): BillingProduct {
    val name = this.name

    val price: String
    val priceAmountMicros: Long
    val currencyCode: String
    val offerToken: String?
    val subscriptionOfferDetailsList: List<SubscriptionOfferDetails>?

    if (productType == BillingProductType.SUBS) {
        val offer = this.subscriptionOfferDetails?.firstOrNull()
        val pricingPhase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()

        price = pricingPhase?.formattedPrice ?: ""
        priceAmountMicros = pricingPhase?.priceAmountMicros ?: 0L
        currencyCode = pricingPhase?.priceCurrencyCode ?: ""
        offerToken = offer?.offerToken

        subscriptionOfferDetailsList = this.subscriptionOfferDetails?.map { offerDetails ->
            SubscriptionOfferDetails(
                offerId = offerDetails.offerId ?: "",
                offerToken = offerDetails.offerToken,
                basePlanId = offerDetails.basePlanId,
                pricingPhases = offerDetails.pricingPhases.pricingPhaseList.map { phase ->
                    PricingPhase(
                        billingPeriod = phase.billingPeriod,
                        billingCycleCount = phase.billingCycleCount,
                        priceFormatted = phase.formattedPrice,
                        priceAmountMicros = phase.priceAmountMicros,
                        recurrenceMode = phase.recurrenceMode
                    )
                }
            )
        }
    } else {
        val oneTimePurchase = this.oneTimePurchaseOfferDetails
        price = oneTimePurchase?.formattedPrice ?: ""
        priceAmountMicros = oneTimePurchase?.priceAmountMicros ?: 0L
        currencyCode = oneTimePurchase?.priceCurrencyCode ?: ""
        offerToken = null
        subscriptionOfferDetailsList = null
    }

    return BillingProduct(
        id = this.productId,
        name = name,
        title = this.title,
        description = this.description,
        price = price,
        priceAmountMicros = priceAmountMicros,
        currencyCode = currencyCode,
        productType = productType,
        offerToken = offerToken,
        subscriptionOfferDetails = subscriptionOfferDetailsList
    )
}

private fun Purchase.toDomainModel(productType: BillingProductType): BillingPurchase {
    return BillingPurchase(
        orderId = this.orderId,
        productId = this.products.firstOrNull() ?: "",
        purchaseToken = this.purchaseToken,
        purchaseTime = this.purchaseTime,
        purchaseState = when (this.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            Purchase.PurchaseState.UNSPECIFIED_STATE -> PurchaseState.UNSPECIFIED
            else -> PurchaseState.UNSPECIFIED
        },
        isAcknowledged = this.isAcknowledged,
        isAutoRenewing = this.isAutoRenewing,
        signature = this.signature,
        originalJson = this.originalJson,
        productName = "",
        productType = productType
    )
}
