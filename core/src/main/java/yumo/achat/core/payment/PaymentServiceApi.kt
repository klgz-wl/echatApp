package yumo.achat.core.payment

import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ServiceFailure
import kotlinx.serialization.json.*
import retrofit2.Response
import retrofit2.http.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

interface PaymentServiceApi {
    @GET suspend fun recover(@Url url: String, @Query("product_id") productId: String?, @Tag session: Session): Response<PaymentEnvelope<RecoveredPaymentData>>
    @POST suspend fun createOrder(@Url url: String, @Body body: CreatePaymentOrderRequest, @Tag session: Session): Response<PaymentEnvelope<CreatedPaymentOrder>>
    @POST suspend fun close(@Url url: String, @Tag session: Session): Response<PaymentEnvelope<PaymentOrderStatus>>
    @POST suspend fun initialize(@Url url: String, @Body body: InitializePaymentRequest, @Tag session: Session): Response<PaymentEnvelope<InitializedPayment>>
    @GET suspend fun status(@Url url: String, @Tag session: Session): Response<PaymentEnvelope<PaymentOrderStatus>>
    @POST suspend fun event(@Url url: String, @Body body: PaymentClientEvent, @Tag session: Session): Response<PaymentEnvelope<PaymentEventReceipt>>
}
interface PaymentRepository {
    suspend fun recover(productId: String, session: Session): RecoveredPaymentOrder?
    suspend fun createOrder(productId: String, requestId: String, session: Session): PaymentBusinessOrder
    suspend fun createOrderWithTrigger(productId: String, requestId: String, trigger: String?, session: Session): PaymentBusinessOrder =
        createOrder(productId, requestId, session)
    suspend fun close(orderId: String, session: Session): PaymentOrderStatus
    suspend fun initialize(orderId: String, session: Session): InitializedPayment
    suspend fun status(orderId: String, session: Session): PaymentOrderStatus
    suspend fun event(orderId: String, event: PaymentClientEvent, session: Session)
}

/** 白名单来自支付接口错误契约，未知内容不得流入日志或界面。 */
internal fun paymentErrorReason(body: String, json: Json): String? = runCatching {
    val value = json.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive
    value?.content?.takeIf { it in setOf("PAYMENT_INITIALIZATION_IN_PROGRESS", "PAYMENT_ORDER_NOT_INITIALIZABLE",
        "PAYMENT_CHANNEL_UNAVAILABLE", "PAYMENT_PRODUCT_UNAVAILABLE", "PAYMENT_RECOVERY_REQUIRES_REVIEW", "missing authorization token", "invalid authorization token", "payment order forbidden") }
}.getOrNull()

@Singleton
class ApiPaymentRepository @Inject constructor(private val api: PaymentServiceApi, private val config: PaymentConfiguration,
    private val sessions: SessionCoordinator, private val json: Json,
    private val billingConfiguration: yumo.achat.core.billing.BillingConfiguration = yumo.achat.core.billing.BillingConfiguration("payment", "manual", true)) : PaymentRepository {
    private fun url(path: String): String {
        if (config.baseUrl.isBlank()) throw PaymentFailure.Unavailable
        return config.baseUrl + "client/payments/" + path
    }
    private fun id(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun owner(session: Session) { if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded }
    private fun <T> read(response: Response<PaymentEnvelope<T>>, session: Session): T {
        owner(session)
        if (!response.isSuccessful) {
            val reason = response.errorBody()?.use { paymentErrorReason(it.string(), json) }
            throw PaymentFailure.Http(response.code(), reason)
        }
        return response.body()?.requireData() ?: throw PaymentFailure.InvalidResponse
    }
    override suspend fun recover(productId: String, session: Session): RecoveredPaymentOrder? {
        owner(session)
        val data = try {
            read(api.recover(url("recover"), productId, session), session)
        } catch (failure: PaymentFailure.Http) {
            if (failure.status == 409 && failure.reason == "PAYMENT_RECOVERY_REQUIRES_REVIEW") throw PaymentFailure.RecoveryRequiresReview
            throw failure
        }
        return data.order?.validate(session.userId)
    }
    override suspend fun initialize(orderId: String, session: Session): InitializedPayment {
        owner(session)
        return read(api.initialize(url("initialize"), InitializePaymentRequest(orderId), session), session).validate(orderId)
    }
    override suspend fun createOrder(productId: String, requestId: String, session: Session): PaymentBusinessOrder =
        createOrderWithTrigger(productId, requestId, null, session)

    override suspend fun createOrderWithTrigger(productId: String, requestId: String, trigger: String?, session: Session): PaymentBusinessOrder {
        owner(session)
        if (runCatching { java.util.UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not())
            throw PaymentFailure.InvalidResponse
        val order = read(api.createOrder(url("orders"), CreatePaymentOrderRequest(productId, requestId,
            (trigger ?: billingConfiguration.defaultTrigger).trim().take(64).ifBlank { "manual" }), session), session)
        if (order.orderId.isBlank() || order.productId != productId || order.status !in setOf("pending", "paid", "failed", "cancelled", "expired") ||
            order.obfuscatedAccountId != session.userId || order.obfuscatedProfileId != order.orderId)
            throw PaymentFailure.InvalidResponse
        return PaymentBusinessOrder(order.orderId, order.obfuscatedAccountId, order.obfuscatedProfileId, order.status)
    }
    override suspend fun close(orderId: String, session: Session): PaymentOrderStatus {
        owner(session)
        return read(api.close(url("${id(orderId)}/close"), session), session).also {
            if (it.orderId != orderId) throw PaymentFailure.InvalidResponse
        }.let { it.copy(failureReason = it.safeFailureReason, retryAction = it.safeRetryAction) }
    }
    override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
        owner(session)
        return read(api.status(url("${id(orderId)}/status"), session), session).also {
            if (it.orderId != orderId) throw PaymentFailure.InvalidResponse
        }.let { it.copy(failureReason = it.safeFailureReason, retryAction = it.safeRetryAction) }
    }
    override suspend fun event(orderId: String, event: PaymentClientEvent, session: Session) {
        owner(session)
        if (!read(api.event(url("${id(orderId)}/client-events"), event, session), session).recorded) throw PaymentFailure.InvalidResponse
    }
}
