package yumo.achat.core.payment

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.POST
import yumo.achat.core.auth.MemoryStorage
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ApiResponse
import yumo.achat.core.network.AuthResponse
import yumo.achat.core.wallet.*

class BusinessPaymentOrderFactoryTest {
    private class Wallet : WalletApi {
        val requests = mutableListOf<CreateOrderRequest>()
        var owner: Session? = null
        var account: String? = "play-account"
        var profile: String? = "play-profile"
        override suspend fun createOrder(body: CreateOrderRequest, session: Session): ApiResponse<CreatedOrder> {
            requests += body; owner = session
            return ApiResponse(0, data = CreatedOrder("business-order", body.productId, "pending", account, profile))
        }
        override suspend fun products(type: String, platform: String, location: String): ApiResponse<ProductsResponse> = error("unexpected products")
        override suspend fun currencies(): ApiResponse<CurrencyResponse> = error("unexpected currencies")
        override suspend fun transactions(page: Int, pageSize: Int): ApiResponse<TransactionsResponse> = error("unexpected transactions")
    }
    private class Legacy : PaymentRepository {
        var request: Triple<String, String, Session>? = null
        override suspend fun createOrder(productId: String, requestId: String, session: Session): PaymentBusinessOrder {
            request = Triple(productId, requestId, session)
            return PaymentBusinessOrder("legacy-order", "legacy-account", "legacy-profile")
        }
        override suspend fun recover(productId: String, session: Session): RecoveredPaymentOrder? = error("unexpected recover")
        override suspend fun close(orderId: String, session: Session): PaymentOrderStatus = error("unexpected close")
        override suspend fun initialize(orderId: String, session: Session): InitializedPayment = error("unexpected initialize")
        override suspend fun status(orderId: String, session: Session): PaymentOrderStatus = error("unexpected status")
        override suspend fun event(orderId: String, event: PaymentClientEvent, session: Session) { error("unexpected event") }
    }
    @Test fun businessCreationUsesWalletAndPreservesReturnedPlayIdentity() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "business-user"))
        val api = Wallet(); val legacy = Legacy()
        val factory = BusinessPaymentOrderFactory(legacy, WalletRepository(api, sessions, WalletConfiguration("diamond", "store", 20)))
        assertEquals("business", factory.creationSource)
        val order = factory.createBusiness("monthly", "local-tracking-request", "video_templates", sessions.current!!)
        assertEquals(PaymentBusinessOrder("business-order", "play-account", "play-profile", "pending"), order)
        assertEquals(listOf(CreateOrderRequest("monthly", "video_templates", "android")), api.requests)
        assertSame(sessions.current, api.owner)
        assertNull(legacy.request)
    }
    @Test fun oldCreationEntryKeepsLegacyRequestIdentity() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "business-user"))
        val api = Wallet(); val legacy = Legacy()
        val factory = BusinessPaymentOrderFactory(legacy, WalletRepository(api, sessions, WalletConfiguration("diamond", "store", 20)))
        val session = sessions.current!!
        assertEquals("legacy-order", factory.create("monthly", "saved-request", session).orderId)
        assertEquals(Triple("monthly", "saved-request", session), legacy.request)
        assertTrue(api.requests.isEmpty())
    }
    @Test fun missingOptionalPlayIdentifiersUseFrozenBusinessOwnerAndOrder() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "business-user"))
        val api = Wallet().apply { account = null; profile = null }
        val factory = BusinessPaymentOrderFactory(Legacy(), WalletRepository(api, sessions, WalletConfiguration("diamond", "store", 20)))
        val order = factory.createBusiness("monthly", "request", "profile_page", sessions.current!!)
        assertEquals("business-user", order.obfuscatedAccountId)
        assertEquals("business-order", order.obfuscatedProfileId)
    }
    @Test fun businessWalletContractHasOnlyProductTriggerAndPlatform() {
        val method = WalletApi::class.java.methods.single { it.name == "createOrder" }
        assertEquals("orders", method.getAnnotation(POST::class.java)!!.value)
        val body = Json.parseToJsonElement(Json.encodeToString(CreateOrderRequest("monthly", "profile_page", "android"))).jsonObject
        assertEquals(setOf("product_id", "trigger", "platform"), body.keys)
        assertEquals("android", body.getValue("platform").jsonPrimitive.content)
        assertFalse(body.containsKey("request_id"))
    }
}
