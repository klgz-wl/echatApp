package com.zorv.core.billing

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可消耗型商品的纯 Kotlin 购买状态机。
 *
 * 平台相关调用由应用层通过闭包注入；状态机只负责顺序、并发与成功边界。
 */
@Singleton
class ConsumablePurchaseOrchestrator @Inject constructor() {
    private val purchaseInProgress = AtomicBoolean(false)

    /** 初始化渠道后只消费已建订单，无法再次调用业务建单接口。 */
    suspend fun <StoreProduct> purchaseExistingOrder(
        orderId: String,
        initializeBilling: suspend () -> PurchaseStepResult<Unit>,
        queryStoreProduct: suspend () -> PurchaseStepResult<StoreProduct>,
        launchPurchase: suspend (String) -> PurchaseStepResult<String>,
        consumePurchase: suspend (String) -> PurchaseStepResult<Unit>,
    ): ConsumablePurchaseResult<StoreProduct> = purchase(initializeBilling, queryStoreProduct,
        { PurchaseStepResult.Success(orderId) }, launchPurchase, consumePurchase)

    suspend fun <StoreProduct> purchase(
        initializeBilling: suspend () -> PurchaseStepResult<Unit>,
        queryStoreProduct: suspend () -> PurchaseStepResult<StoreProduct>,
        createOrder: suspend () -> PurchaseStepResult<String>,
        launchPurchase: suspend (orderId: String) -> PurchaseStepResult<String>,
        consumePurchase: suspend (purchaseToken: String) -> PurchaseStepResult<Unit>,
    ): ConsumablePurchaseResult<StoreProduct> {
        if (!purchaseInProgress.compareAndSet(false, true)) {
            return ConsumablePurchaseResult.InProgress
        }

        return try {
            val initializeResult = initializeBilling()
            if (initializeResult is PurchaseStepResult.Failure) {
                return initializeResult.asPurchaseFailure(ConsumablePurchaseStage.BILLING_INITIALIZATION)
            }

            val queryResult = queryStoreProduct()
            if (queryResult is PurchaseStepResult.Failure) {
                return queryResult.asPurchaseFailure(ConsumablePurchaseStage.PRODUCT_QUERY)
            }
            val storeProduct = (queryResult as PurchaseStepResult.Success).value

            val orderResult = createOrder()
            if (orderResult is PurchaseStepResult.Failure) {
                return orderResult.asPurchaseFailure(ConsumablePurchaseStage.ORDER_CREATION)
            }
            val orderId = (orderResult as PurchaseStepResult.Success).value

            val purchaseResult = launchPurchase(orderId)
            if (purchaseResult is PurchaseStepResult.Failure) {
                return purchaseResult.asPurchaseFailure(ConsumablePurchaseStage.PURCHASE)
            }
            if (purchaseResult is PurchaseStepResult.Pending) {
                return ConsumablePurchaseResult.Pending(storeProduct)
            }
            val purchaseToken = (purchaseResult as PurchaseStepResult.Success).value

            val consumeResult = consumePurchase(purchaseToken)
            if (consumeResult is PurchaseStepResult.Failure) {
                return consumeResult.asPurchaseFailure(ConsumablePurchaseStage.CONSUMPTION)
            }

            ConsumablePurchaseResult.Success(storeProduct)
        } finally {
            purchaseInProgress.set(false)
        }
    }
}

enum class ConsumablePurchaseStage {
    BILLING_INITIALIZATION,
    PRODUCT_QUERY,
    ORDER_CREATION,
    PURCHASE,
    CONSUMPTION,
}

sealed interface PurchaseStepResult<out T> {
    data class Success<T>(val value: T) : PurchaseStepResult<T>
    data class Pending<T>(val value: T) : PurchaseStepResult<T>
    data class Failure(val message: String? = null, val code: Int? = null) : PurchaseStepResult<Nothing>
}

sealed interface ConsumablePurchaseResult<out StoreProduct> {
    data class Success<StoreProduct>(val storeProduct: StoreProduct) : ConsumablePurchaseResult<StoreProduct>

    data class Failure(
        val stage: ConsumablePurchaseStage,
        val message: String?,
        val code: Int? = null,
    ) : ConsumablePurchaseResult<Nothing>

    data class Pending<StoreProduct>(val storeProduct: StoreProduct) : ConsumablePurchaseResult<StoreProduct>

    data object InProgress : ConsumablePurchaseResult<Nothing>
}

private fun PurchaseStepResult.Failure.asPurchaseFailure(
    stage: ConsumablePurchaseStage,
): ConsumablePurchaseResult.Failure = ConsumablePurchaseResult.Failure(stage, message, code)

fun <T> BillingResult<T>.toPurchaseStep(): PurchaseStepResult<T> =
    data?.takeIf { isSuccess }
        ?.let { PurchaseStepResult.Success(it) }
        ?: PurchaseStepResult.Failure(errorMessage, responseCode)
