package yumo.achat.core.payment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class PurchaseFlow { SERVICE, LEGACY }
data class PurchaseFlowConfiguration(val flow: PurchaseFlow)

/** 只选择新购买使用的策略；失败向上返回，不隐式切换渠道或重建另一种订单。 */
@Singleton
class PurchaseFlowDispatcher @Inject constructor(private val config: PurchaseFlowConfiguration) {
    private val lock = Mutex()
    suspend fun resumeOwnedOrder(action: suspend () -> Unit) = lock.withLock { action() }

    suspend fun dispatch(service: suspend () -> Unit, legacy: suspend () -> Unit) {
        if (!lock.tryLock()) return
        try {
            when (config.flow) {
                PurchaseFlow.SERVICE -> service()
                PurchaseFlow.LEGACY -> legacy()
            }
        } finally { lock.unlock() }
    }
}
