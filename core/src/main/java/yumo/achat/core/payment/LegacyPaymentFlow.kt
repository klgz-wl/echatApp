package yumo.achat.core.payment

import android.app.Activity
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.billing.CoinPurchaseController
import yumo.achat.core.billing.CoinPurchaseStatus
import yumo.achat.core.wallet.CoinProduct
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 参考旧流程：查 Play 商品 → 业务建单 → SDK 购买 → 消费与真实钱包刷新。 */
@Singleton
class LegacyPaymentFlow @Inject constructor(private val orders: LegacyPaymentRepository,
    private val purchases: CoinPurchaseController, private val sessions: SessionCoordinator) {
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val failureEvents = failures.asSharedFlow()
    suspend fun buy(activity: Activity, product: CoinProduct, source: String) {
        val epoch = sessions.current?.epoch ?: return
        purchases.buyLegacy(activity, product, epoch, source) { orders.createOrder(product.id, it) }
        val result = purchases.state.value
        if (sessions.current?.epoch == epoch && result.epoch == epoch && result.status == CoinPurchaseStatus.FAILED)
            failures.tryEmit(epoch)
    }
}
