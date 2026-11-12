package yumo.achat.core.payment

import yumo.achat.core.auth.*
import yumo.achat.core.network.AuthResponse
import yumo.achat.core.network.ServiceFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PaymentRepositoryTest {
    @Test fun `definite product unavailable create reason survives safe decoding`() {
        assertEquals("PAYMENT_RECOVERY_REQUIRES_REVIEW", paymentErrorReason("""{"error":"PAYMENT_RECOVERY_REQUIRES_REVIEW"}""", Json))
        assertEquals("PAYMENT_PRODUCT_UNAVAILABLE", paymentErrorReason("""{"error":"PAYMENT_PRODUCT_UNAVAILABLE"}""", Json))
        assertNull(paymentErrorReason("""{"error":"PRODUCT_UNAVAILABLE"}""", Json))
        assertNull(paymentErrorReason("""{"error":"raw provider response"}""", Json))
    }
    private class Api : PaymentServiceApi {
        var recovered: RecoveredPaymentOrder? = null
        var recoverProduct: String? = null
        var recoveryReviewRequired = false
        override suspend fun recover(url: String, productId: String?, session: Session): Response<PaymentEnvelope<RecoveredPaymentData>> {
            urls += url; owner = session
            recoverProduct = productId
            if (recoveryReviewRequired) return Response.error(409,
                """{"error":"PAYMENT_RECOVERY_REQUIRES_REVIEW"}""".toResponseBody("application/json".toMediaType()))
            return Response.success(PaymentEnvelope(RecoveredPaymentData(recovered)))
        }
        var createBody: CreatePaymentOrderRequest? = null
        var createProduct = "coins"
        var createUser = "user"
        override suspend fun createOrder(url: String, body: CreatePaymentOrderRequest, session: Session): Response<PaymentEnvelope<CreatedPaymentOrder>> {
            urls += url; owner = session; createBody = body
            return Response.success(PaymentEnvelope(CreatedPaymentOrder("order", createProduct, "pending", createUser, "order")))
        }
        override suspend fun close(url: String, session: Session): Response<PaymentEnvelope<PaymentOrderStatus>> {
            urls += url; return Response.success(PaymentEnvelope(PaymentOrderStatus("order", "pending")))
        }
        val urls = mutableListOf<String>(); var owner: Session? = null; var failure: Int? = null
        override suspend fun initialize(url: String, body: InitializePaymentRequest, session: Session): Response<PaymentEnvelope<InitializedPayment>> {
            urls += url; owner = session
            failure?.let { return Response.error(it, """{"error":"PAYMENT_INITIALIZATION_IN_PROGRESS"}""".toResponseBody("application/json".toMediaType())) }
            return Response.success(PaymentEnvelope(InitializedPayment(body.orderId,"official","google_play","sdk",PaymentSdkParams("returned"))))
        }
        override suspend fun status(url: String, session: Session): Response<PaymentEnvelope<PaymentOrderStatus>> {
            urls += url; return Response.success(PaymentEnvelope(PaymentOrderStatus("wrong","paid","fulfilled")))
        }
        override suspend fun event(url: String, body: PaymentClientEvent, session: Session): Response<PaymentEnvelope<PaymentEventReceipt>> {
            urls += url; return Response.success(PaymentEnvelope(PaymentEventReceipt(false)))
        }
    }
    @Test fun `recovery GET validates owner snapshots and null order envelope`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage()); sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val api = Api(); val config = PaymentConfiguration("https://test.example/payment-api/v1/", "test", 10, 600, 3000, 100, 3, 5000)
        val repo = ApiPaymentRepository(api, config, sessions, Json)
        assertNull(repo.recover("selected-product", sessions.current!!))
        assertEquals("selected-product", api.recoverProduct)
        assertEquals("https://test.example/payment-api/v1/client/payments/recover", api.urls.single())
        val order = Json.decodeFromString<PaymentEnvelope<RecoveredPaymentData>>("""{"data":{"order":{"order_id":"order","product_id":"server-product","status":"pending","obfuscated_account_id":"user","obfuscated_profile_id":"order","amount":7.25,"currency":"USD","payment_method":"google_play","provider_product_id":"server-sku","fulfillment_status":"pending","trigger":"manual"}}}""").requireData().order!!
        api.recovered = order
        assertEquals(order, repo.recover("selected-product", sessions.current!!))
        for (invalid in listOf(order.copy(obfuscatedAccountId = "other"), order.copy(obfuscatedProfileId = "wrong"),
            order.copy(amount = Double.NaN), order.copy(amount = -1.0), order.copy(currency = "bad"),
            order.copy(productId = ""), order.copy(status = "cancelled"), order.copy(status = "paid", fulfillmentStatus = "fulfilled"))) {
            api.recovered = invalid
            try { repo.recover("selected-product", sessions.current!!); fail("Invalid recovery accepted") } catch (_: PaymentFailure.InvalidResponse) { }
        }
        api.recoveryReviewRequired = true
        try { repo.recover("selected-product", sessions.current!!); fail("Review required ignored") }
        catch (_: PaymentFailure.RecoveryRequiresReview) { }
    }
    @Test fun `service order uses durable UUID and configured trigger with ownership validation`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage()); sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val api = Api(); val config = PaymentConfiguration("https://test.example/payment-api/v1/", "test", 10, 600, 3000, 100, 3, 5000)
        val repo = ApiPaymentRepository(api, config, sessions, Json,
            yumo.achat.core.billing.BillingConfiguration("billing", "manual"))
        val key = java.util.UUID.randomUUID().toString(); val session = sessions.current!!
        assertEquals("order", repo.createOrder("coins", key, session).orderId)
        assertEquals(CreatePaymentOrderRequest("coins", key, "manual"), api.createBody)
        assertEquals("https://test.example/payment-api/v1/client/payments/orders", api.urls.last())
        assertEquals("pending", repo.close("order", session).status)
        assertTrue(api.urls.last().endsWith("/order/close"))
        api.createProduct = "other"
        try { repo.createOrder("coins", key, session); fail() } catch (_: PaymentFailure.InvalidResponse) { }
        api.createProduct = "coins"; api.createUser = "other"
        try { repo.createOrder("coins", key, session); fail() } catch (_: PaymentFailure.InvalidResponse) { }
    }
    @Test fun `subscription uses frozen entry trigger rather than global default`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage()); sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val api = Api()
        val config = PaymentConfiguration("https://test.example/payment-api/v1/", "test", 10, 600, 3000, 100, 3, 5000)
        val repo = ApiPaymentRepository(api, config, sessions, Json,
            yumo.achat.core.billing.BillingConfiguration("billing", "manual"))
        val request = java.util.UUID.randomUUID().toString()
        repo.createOrderWithTrigger("coins", request, "video_templates", sessions.current!!)
        assertEquals("video_templates", api.createBody!!.trigger)
        repo.createOrderWithTrigger("coins", request, null, sessions.current!!)
        assertEquals("manual", api.createBody!!.trigger)
    }
    @Test fun `service trigger is bounded at repository boundary`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage()); sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val api = Api(); val config = PaymentConfiguration("https://test.example/payment-api/v1/", "test", 10, 600, 3000, 100, 3, 5000)
        val repo = ApiPaymentRepository(api, config, sessions, Json,
            yumo.achat.core.billing.BillingConfiguration("billing", "x".repeat(200)))
        repo.createOrder("coins", java.util.UUID.randomUUID().toString(), sessions.current!!)
        assertEquals(64, api.createBody!!.trigger.length)
    }
    @Test fun `支付路径独立且401之外的错误不会重试建单`() = runBlocking {
        val sessions=SessionCoordinator(MemoryStorage());sessions.saveLogin(AuthResponse("token","refresh","user"))
        val api=Api();val config=PaymentConfiguration("https://test.example/payment-api/v1/","test",10,600,3000,100,3,5000)
        val repo=ApiPaymentRepository(api,config,sessions,Json)
        val owner=sessions.current!!
        assertEquals("returned",repo.initialize("order",owner).sdkParams?.productId)
        assertEquals("https://test.example/payment-api/v1/client/payments/initialize",api.urls.single());assertEquals(owner,api.owner)
        api.failure=409
        try { repo.initialize("order",owner);fail() } catch (error: PaymentFailure.Http) { assertEquals(409,error.status);assertFalse(error.cannotInitialize) }
        assertEquals(2,api.urls.size)
        try { repo.status("order/with space",owner);fail() } catch (_: PaymentFailure.InvalidResponse) { }
        assertTrue(api.urls.last().endsWith("order%2Fwith%20space/status"))
        try { repo.event("order",PaymentClientEvent("link_ok",occurredAt="2026-09-14T00:00:00Z"),owner);fail() } catch (_: PaymentFailure.InvalidResponse) { }
        val before=api.urls.size;sessions.saveLogin(AuthResponse("new","refresh","other"))
        try { repo.initialize("order",owner);fail() } catch (_: ServiceFailure.Superseded) { }
        assertEquals(before,api.urls.size)
        val disabled=ApiPaymentRepository(api,config.copy(baseUrl=""),sessions,Json)
        try { disabled.initialize("order",sessions.current!!);fail() } catch (_: PaymentFailure.Unavailable) { }
        assertEquals(before,api.urls.size)
    }
}
