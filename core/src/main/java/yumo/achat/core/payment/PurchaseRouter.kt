package yumo.achat.core.payment

import android.app.Activity
import yumo.achat.core.analytics.*
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.billing.CoinPurchaseController
import yumo.achat.core.billing.blocksNewCoinPurchase
import yumo.achat.core.wallet.CoinProduct
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

enum class RechargeNotificationRoute { LegacyFirst, Handled, Unknown }

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
            payments.state.value.record?.stage == PaymentStage.OFFICIAL_READY ||
            blocksNewCoinPurchase(purchases.state.value.status)
        ) return
        scope.launch {
            if (sessions.current?.epoch != epoch || activity.isFinishing || activity.isDestroyed) return@launch
            dispatcher.dispatch(service = { service.buy(product, source) }, legacy = { legacy.buy(activity, product, source) })
        }
    }

    suspend fun launchOfficial(activity: Activity, key: String, epoch: String) =
        dispatcher.resumeOwnedOrder { service.launchOfficial(activity, key, epoch) }

    /** Play 恢复事件只让新流程查单，不能提前消费旧流程的真实到账通知。 */
    suspend fun recoveredPurchase(orderId: String?) {
        payments.engine.recharge(orderId)
    }

    /** 明确区分首次旧流程到账、已由 Core 接管及未知通知，避免未知订单触发成功 UI。 */
    suspend fun rechargeNotification(orderId: String?): RechargeNotificationRoute {
        val session = sessions.current ?: return RechargeNotificationRoute.Unknown
        return try {
            when (legacyOrders.recharge(orderId, session.userId)) {
                LegacyRechargeMatch.FIRST -> if (sessions.current?.epoch == session.epoch) {
                    RechargeNotificationRoute.LegacyFirst
                } else {
                    RechargeNotificationRoute.Handled
                }
                LegacyRechargeMatch.DUPLICATE -> RechargeNotificationRoute.Handled
                LegacyRechargeMatch.UNKNOWN -> if (payments.engine.recharge(orderId)) {
                    RechargeNotificationRoute.Handled
                } else {
                    RechargeNotificationRoute.Unknown
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // 无法确认归属时只刷新钱包，不把新流程通知误判为旧流程到账。
            payments.refreshWallet()
            RechargeNotificationRoute.Unknown
        }
    }

    suspend fun reconcileLegacyWalletTransactions(orderIds: Set<String>): String? {
        val session = sessions.current ?: return null
        return try {
            legacyOrders.rechargeFirstMatching(orderIds, session.userId)
                ?.takeIf { sessions.current?.epoch == session.epoch }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun hasPendingLegacyOrder(): Boolean {
        val session = sessions.current ?: return false
        return try {
            legacyOrders.hasPending(session.userId) && sessions.current?.epoch == session.epoch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
