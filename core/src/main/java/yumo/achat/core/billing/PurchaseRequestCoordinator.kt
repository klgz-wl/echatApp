package yumo.achat.core.billing

import kotlinx.coroutines.CompletableDeferred

/**
 * 将 Google Play 的全局购买回调绑定到单次购买请求。
 *
 * BillingClient 的 listener 是全局的，但页面发起的购买必须按商品隔离；已经超时或结束的
 * 请求不能把迟到回调交给下一笔购买。
 */
internal class PurchaseRequestCoordinator {
    private val lock = Any()
    private var activeRequest: PurchaseRequest? = null

    fun start(
        productId: String,
        productType: BillingProductType,
        orderId: String?,
        userId: String? = null,
    ): PurchaseRequest = synchronized(lock) {
        if (activeRequest != null) {
            return@synchronized PurchaseRequest.completed(
                productId = productId,
                productType = productType,
                orderId = orderId,
                userId = userId,
                result = BillingResult.error(
                    BillingResponseCode.ERROR,
                    "Another purchase is already in progress",
                ),
            )
        }

        PurchaseRequest(productId, productType, orderId, userId).also { activeRequest = it }
    }

    fun findMatching(
        productIds: List<String>,
        orderId: String?,
    ): PurchaseRequest? = synchronized(lock) {
        activeRequest?.takeIf { request ->
            request.productId in productIds &&
                (request.orderId == null || request.orderId == orderId)
        }
    }

    fun isActive(request: PurchaseRequest): Boolean = synchronized(lock) {
        activeRequest === request
    }

    fun activeRequest(): PurchaseRequest? = synchronized(lock) { activeRequest }

    fun complete(
        request: PurchaseRequest,
        result: BillingResult<BillingPurchase>,
    ): Boolean {
        val shouldComplete = synchronized(lock) {
            if (activeRequest !== request) {
                false
            } else if (result.data != null && result.data.productId != request.productId) {
                false
            } else {
                activeRequest = null
                true
            }
        }
        return shouldComplete && request.complete(result)
    }

    fun completeActive(result: BillingResult<BillingPurchase>): Boolean {
        val request = synchronized(lock) { activeRequest } ?: return false
        return complete(request, result)
    }

}

internal class PurchaseRequest(
    val productId: String,
    val productType: BillingProductType,
    val orderId: String?,
    val userId: String?,
    private val deferred: CompletableDeferred<BillingResult<BillingPurchase>> = CompletableDeferred(),
) {
    fun complete(result: BillingResult<BillingPurchase>): Boolean = deferred.complete(result)

    suspend fun await(): BillingResult<BillingPurchase> = deferred.await()

    companion object {
        fun completed(
            productId: String,
            productType: BillingProductType,
            orderId: String?,
            userId: String?,
            result: BillingResult<BillingPurchase>,
        ): PurchaseRequest = PurchaseRequest(productId, productType, orderId, userId).apply { complete(result) }
    }
}
