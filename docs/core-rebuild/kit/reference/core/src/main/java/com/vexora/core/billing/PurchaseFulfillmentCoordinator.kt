package com.vexora.core.billing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/** 同一 purchaseToken 的 consume/ack 只执行一次，且不继承页面调用方的取消。 */
internal class PurchaseFulfillmentCoordinator(
    private val scope: CoroutineScope,
    private val onFulfilled: (String) -> Unit,
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<BillingResult<Unit>>>()
    private val fulfilledTokens = mutableSetOf<String>()

    suspend fun fulfill(
        purchaseToken: String,
        operation: suspend () -> BillingResult<Unit>,
    ): BillingResult<Unit> {
        val work = synchronized(lock) {
            if (purchaseToken in fulfilledTokens) {
                // 迟到的重复回调可能重新写入同一 token；幂等返回前再次收敛持久记录。
                onFulfilled(purchaseToken)
                return@synchronized null
            }
            inFlight[purchaseToken] ?: scope.async {
                val result = operation()
                if (result.isSuccess) {
                    onFulfilled(purchaseToken)
                    synchronized(lock) { fulfilledTokens += purchaseToken }
                }
                result
            }.also { deferred ->
                inFlight[purchaseToken] = deferred
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight[purchaseToken] === deferred) {
                            inFlight.remove(purchaseToken)
                        }
                    }
                }
            }
        }
        return work?.await() ?: BillingResult.success(Unit)
    }
}
