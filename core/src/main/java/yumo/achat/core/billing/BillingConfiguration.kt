package yumo.achat.core.billing

data class BillingConfiguration(
    val storageName: String,
    val defaultTrigger: String,
    val backendOwnedFulfillment: Boolean = false,
)
