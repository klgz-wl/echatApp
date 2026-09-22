package com.vexora.core.billing

enum class BillingProductType {

    IN_APP,


    SUBS;


    fun toBillingType(): String {
        return when (this) {
            IN_APP -> BillingClient.ProductType.INAPP
            SUBS -> BillingClient.ProductType.SUBS
        }
    }


    companion object {
        fun fromProductType(productType: String?): BillingProductType {
            return when (productType?.lowercase()) {
                "diamond", "coins", "gem" -> IN_APP
                "membership", "subscription", "vip", "sub" -> SUBS
                else -> IN_APP // default to in-app
            }
        }
    }
}

object BillingClient {
    object ProductType {
        const val INAPP = "inapp"
        const val SUBS = "subs"
    }
}
