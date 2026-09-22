package yumo.achat.core.payment

import android.app.Activity
import yumo.achat.core.analytics.*
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.billing.CoinPurchaseController
import yumo.achat.core.billing.CoinPurchaseStatus
import yumo.achat.core.wallet.CoinProduct
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/** 页面统一入口；配置只影响新购买，恢复与到账按持久化订单归属分派。 */
@Singleton
class PurchaseRouter @Inject constructor(private val dispatcher: PurchaseFlowDispatcher,
    private val service: ServicePaymentFlow, private val legacy: LegacyPaymentFlow,
    private val legacyOrders: LegacyOrderRegistry, private val payments: PaymentCoordinator,
    private val purchases: CoinPurchaseController, private val sessions: SessionCoordinator, private val events: EventTracker) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val failures = merge(payments.engine.failureEvents, legacy.failureEvents)

    fun buy(activity: Activity, product: CoinProduct, source: String) {
        events.track("click_package", buildMap {
            put("package_id", product.id); put("entry_source", source)
            putAll(paymentPriceProperties(product.displayPrice, product.currency, "catalog_quote"))
            product.playProductId?.takeIf { it.isNotBlank() }?.let { put("af_content_id", it) }
            product.coins?.let { put("diamond_amount", it) }; put("bonus_amount", product.displayBonus)
        })
        val epoch = sessions.current?.epoch ?: return
        if (payments.state.value.busy || payments.state.value.checkoutVisible ||
            payments.state.value.record?.stage == PaymentStage.OFFICIAL_READY || purchases.state.value.status == CoinPurchaseStatus.BUSY) return
        scope.launch {
            if (sessions.current?.epoch != epoch || activity.isFinishing || activity.isDestroyed) return@launch
            dispatcher.dispatch(service = { service.buy(product, source) }, legacy = { legacy.buy(activity, product, source) })
        }
    }

    suspend fun launchOfficial(activity: Activity, key: String, epoch: String) =
        dispatcher.resumeOwnedOrder { service.launchOfficial(activity, key, epoch) }

    /** true 表示已接管通知；false 保留参考旧流程的成功提示及返回生成页行为。 */
    suspend fun recharge(orderId: String?): Boolean {
        val session = sessions.current ?: return true
        return try {
            when (legacyOrders.recharge(orderId, session.userId)) {
                LegacyRechargeMatch.FIRST -> sessions.current?.epoch != session.epoch
                LegacyRechargeMatch.DUPLICATE -> true
                LegacyRechargeMatch.UNKNOWN -> payments.engine.recharge(orderId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // 无法确认归属时只刷新钱包，不把新流程通知误判为旧流程到账。
            payments.refreshWallet()
            true
        }
    }
}
