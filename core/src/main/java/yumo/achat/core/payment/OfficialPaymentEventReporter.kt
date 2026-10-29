package yumo.achat.core.payment

import yumo.achat.core.auth.Session
import yumo.achat.core.billing.BillingResponseCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** SDK 诊断事件仅供排障；网络失败不能改变购买、查单或履约状态。 */
@Singleton
class OfficialPaymentEventReporter @Inject constructor(private val repository: ApiPaymentRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ordering = Mutex()

    fun report(orderId: String, eventType: String, session: Session, errorCode: String? = null) {
        if (orderId.isBlank()) return
        val event = OfficialPaymentEvent(eventType, errorCode, UUID.randomUUID().toString())
        scope.launch {
            ordering.withLock {
                try { repository.officialEvent(orderId, event, session) }
                catch (_: Exception) { /* 诊断上报不参与支付判定。 */ }
            }
        }
    }
}

fun officialSdkResultEvent(responseCode: Int): String? = when (responseCode) {
    BillingResponseCode.OK -> null
    BillingResponseCode.CANCELLED -> "official_sdk_cancelled"
    else -> "official_sdk_failed"
}
