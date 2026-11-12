package yumo.achat.core.payment

import javax.inject.Inject
import javax.inject.Singleton
import yumo.achat.core.auth.Session
import yumo.achat.core.wallet.CreatedOrder
import yumo.achat.core.wallet.WalletRepository

internal enum class OrderCreationSource { PaymentService, Business }
internal fun orderCreationSource(source: String?): OrderCreationSource? = when (source) {
    null, "payment_service" -> OrderCreationSource.PaymentService
    "business" -> OrderCreationSource.Business
    else -> null
}
internal fun canReplayOrderCreation(source: String?, started: Boolean): Boolean = when (orderCreationSource(source)) {
    OrderCreationSource.PaymentService -> true
    OrderCreationSource.Business -> !started
    null -> false
}

internal fun businessPaymentOrder(created: CreatedOrder, productId: String, owner: String): PaymentBusinessOrder {
    if (created.orderId.isBlank() || created.productId != productId ||
        created.status !in setOf("pending", "paid", "failed", "cancelled", "expired") ||
        created.obfuscatedAccountId?.isBlank() == true || created.obfuscatedProfileId?.isBlank() == true) {
        throw PaymentFailure.InvalidResponse
    }
    return PaymentBusinessOrder(created.orderId, created.obfuscatedAccountId ?: owner,
        created.obfuscatedProfileId ?: created.orderId, created.status)
}

/** New purchases use the existing business API; historical intents retain their original creator. */
@Singleton
class BusinessPaymentOrderFactory @Inject constructor(
    private val legacy: PaymentRepository,
    private val wallet: WalletRepository,
) : PaymentOrderFactory {
    override val creationSource: String = "business"
    override suspend fun create(productId: String, requestId: String, session: Session): PaymentBusinessOrder =
        legacy.createOrder(productId, requestId, session)
    override suspend fun createBusiness(productId: String, requestId: String, trigger: String, session: Session): PaymentBusinessOrder =
        businessPaymentOrder(wallet.createOrder(productId, trigger, session), productId, session.userId)
}
