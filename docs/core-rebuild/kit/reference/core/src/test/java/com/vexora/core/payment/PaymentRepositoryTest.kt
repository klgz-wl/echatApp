package com.vexora.core.payment

import com.vexora.core.auth.*
import com.vexora.core.network.AuthResponse
import com.vexora.core.network.ServiceFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PaymentRepositoryTest {
    private class Api : PaymentServiceApi {
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
