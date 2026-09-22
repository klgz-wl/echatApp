package com.vexora.core.billing

data class BillingPurchase(
    val orderId: String?,
    val productId: String,
    val purchaseToken: String,
    val purchaseTime: Long,
    val purchaseState: PurchaseState,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean = false,
    val signature: String,
    val originalJson: String,
    val productName: String = "",
    val productType: BillingProductType
) { override fun toString() = "BillingPurchase(购买凭据已隐藏)" }

enum class PurchaseState {

    PURCHASED,


    PENDING,


    UNSPECIFIED
}

data class BillingResult<out T> (
    val responseCode: Int,
    val data: T? = null,
    val errorMessage: String? = null,
    val debugMessage: String? = null
) {
    val isSuccess: Boolean get() = responseCode == BillingResponseCode.OK && data != null
    val isError: Boolean get() = !isSuccess

    companion object {
        fun <T> success(data: T): BillingResult<T> = BillingResult(BillingResponseCode.OK, data)
        fun <T> error(code: Int, message: String? = null, debugMessage: String? = null): BillingResult<T> =
            BillingResult(code, null, message, debugMessage)
    }
}

object BillingResponseCode {
    const val SERVICE_DISCONNECTED = -1
    const val FEATURE_NOT_SUPPORTED = -2
    const val OK = 0
    const val CANCELLED = 1
    const val SERVICE_UNAVAILABLE = 2
    const val BILLING_UNAVAILABLE = 3
    const val ITEM_UNAVAILABLE = 4
    const val DEVELOPER_ERROR = 5
    const val ERROR = 6
    const val ITEM_ALREADY_OWNED = 7
    const val ITEM_NOT_OWNED = 8
    const val NETWORK_ERROR = 12

    fun describe(code: Int): String = when (code) {
        SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED(-1)"
        FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED(-2)"
        OK -> "OK(0)"
        CANCELLED -> "CANCELLED(1)"
        SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE(2)"
        BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE(3)"
        ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE(4)"
        DEVELOPER_ERROR -> "DEVELOPER_ERROR(5)"
        ERROR -> "ERROR(6)"
        ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED(7)"
        ITEM_NOT_OWNED -> "ITEM_NOT_OWNED(8)"
        NETWORK_ERROR -> "NETWORK_ERROR(12)"
        else -> "UNKNOWN($code)"
    }
}
