package com.vexora.core.payment

import android.app.Activity
import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.billing.CoinPurchaseController
import com.vexora.core.billing.CoinPurchaseStatus
import com.vexora.core.billing.ConsumablePurchaseStage
import com.vexora.core.wallet.CoinProduct
import javax.inject.Inject
import javax.inject.Singleton

/** 新流程：业务建单 → 初始化选择渠道；只有官方渠道可以调用 Play。 */
@Singleton
class ServicePaymentFlow @Inject constructor(private val engine: PaymentEngine,
    private val purchases: CoinPurchaseController, private val sessions: SessionCoordinator) {
    suspend fun buy(product: CoinProduct, source: String) = engine.buy(product.id, source, product)

    /** 即使配置切为旧流程，已初始化的新订单仍使用原订单及服务器返回的 SKU。 */
    suspend fun launchOfficial(activity: Activity, key: String, epoch: String) {
        if (sessions.current?.epoch != epoch || activity.isFinishing || activity.isDestroyed) return
        val order = engine.claimOfficial(key) ?: return
        purchases.buy(activity, order.orderId!!, order.initialized!!.sdkParams!!.productId!!, epoch, order.source, order.productId)
        val result = purchases.state.value
        if (result.epoch != epoch) return
        val retry = result.status == CoinPurchaseStatus.CANCELLED ||
            (result.status == CoinPurchaseStatus.FAILED && result.failureStage in setOf(
                ConsumablePurchaseStage.BILLING_INITIALIZATION, ConsumablePurchaseStage.PRODUCT_QUERY))
        engine.officialResult(key, retry, result.status == CoinPurchaseStatus.COMPLETED,
            failed = result.status == CoinPurchaseStatus.FAILED)
    }
}
