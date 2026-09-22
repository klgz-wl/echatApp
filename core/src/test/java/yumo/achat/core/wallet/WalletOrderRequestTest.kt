package yumo.achat.core.wallet

import yumo.achat.core.auth.MemoryStorage
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.config.*
import yumo.achat.core.network.AuthResponse
import yumo.achat.core.network.NetworkModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class WalletOrderRequestTest {
    @Test fun `真实转换器发送安卓平台并保留服务端业务订单号`() = runBlocking {
        val json = NetworkModule.json()
        var sent: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/api/v1/orders", chain.request().url.encodedPath)
            sent = json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"order_id":"server-order","product_id":"coins","status":"pending"}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config = CoreRuntimeConfig(NetworkConfig("https://test.example/api/v1/", "wss://test.example/", "https://test.example/"),
            StorageConfig("db", "prefs"), DiagnosticsConfig(false))
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val repository = WalletRepository(NetworkModule.walletApi(client, config, json), sessions, WalletConfiguration("diamond", "store", 20))
        val order = repository.createOrder("coins", "store", sessions.current!!)
        assertEquals("android", sent?.get("platform")?.jsonPrimitive?.content)
        assertEquals("coins", sent?.get("product_id")?.jsonPrimitive?.content)
        assertEquals("store", sent?.get("trigger")?.jsonPrimitive?.content)
        assertEquals("server-order", order.orderId)
    }
}
