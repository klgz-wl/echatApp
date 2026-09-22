package com.vexora.core.wallet

import com.vexora.core.network.ApiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Tag
import com.vexora.core.auth.Session

@Serializable
data class CoinProduct(val id: String, val type: String, val name: String,
    val price: Double, val currency: String = "USD",
    @SerialName("value") val coins: Long? = null,
    @SerialName("bonus_value") val bonus: Long? = null,
    @SerialName("google_product_id") val googleProductId: String? = null,
    @SerialName("is_subscription") val subscription: Boolean = false,
    @SerialName("third_party_product_id") val thirdPartyProductId: String? = null,
    @SerialName("first_buy_price") val firstBuyPrice: Double? = null,
    @SerialName("first_buy_bonus_value") val firstBuyBonus: Long? = null,
    @SerialName("is_first_buy_promotion") val firstBuyPromotion: Boolean = false,
    @SerialName("original_price") val originalPrice: Double? = null) {
    val playProductId: String? get() = googleProductId ?: thirdPartyProductId
    val displayPrice: Double? get() = if (firstBuyPromotion) firstBuyPrice ?: originalPrice else originalPrice
    val displayBonus: Long get() = if (firstBuyPromotion) firstBuyBonus ?: 0 else bonus ?: 0
    val showBonus: Boolean get() = firstBuyPromotion || displayBonus > 0
    val canDisplay: Boolean get() = coins != null && displayPrice != null
}
@Serializable data class ProductsResponse(val products: List<CoinProduct>)
@Serializable data class CurrencyResponse(@SerialName("user_id") val userId: String,
    @SerialName("diamond_balance") val balance: Long)
@Serializable
data class CoinTransaction(val id: String, val type: String, val amount: Long,
    val description: String? = null, val category: String? = null,
    @SerialName("created_at") val createdAt: String)
@Serializable data class TransactionsResponse(val transactions: List<CoinTransaction>, val total: Int,
    val page: Int, @SerialName("page_size") val pageSize: Int)
@Serializable data class CreateOrderRequest(@SerialName("product_id") val productId: String,
    val trigger: String, val platform: String)
@Serializable data class CreatedOrder(@SerialName("order_id") val orderId: String,
    @SerialName("product_id") val productId: String, val status: String)

/** 接口仍使用 diamond 字段；展示为金币，数值不做换算。 */
interface WalletApi {
    @GET("products") suspend fun products(@Query("product_type") type: String,
        @Query("platform") platform: String, @Query("location") location: String): ApiResponse<ProductsResponse>
    @GET("user/currencies") suspend fun currencies(): ApiResponse<CurrencyResponse>
    @GET("wallet/transactions") suspend fun transactions(@Query("page") page: Int,
        @Query("page_size") pageSize: Int): ApiResponse<TransactionsResponse>
    @POST("orders") suspend fun createOrder(@Body body: CreateOrderRequest, @Tag session: Session): ApiResponse<CreatedOrder>
}

data class WalletConfiguration(val productType: String, val location: String, val pageSize: Int) {
    init { require(productType.isNotBlank() && location.isNotBlank() && pageSize > 0) }
}
