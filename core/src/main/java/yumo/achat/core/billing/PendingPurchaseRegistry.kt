package yumo.achat.core.billing

internal data class PendingPurchaseRecord(
    val productId: String,
    val orderId: String?,
    val productType: BillingProductType,
    val purchaseToken: String?,
    val userId: String? = null,
) { override fun toString() = "PendingPurchaseRecord(购买凭据已隐藏)" }

/** 负责持久化层之外的 pending 交易关联，匹配时优先使用 token/order，避免同 SKU 串单。 */
internal class PendingPurchaseRegistry {
    private val lock = Any()
    private val records = mutableListOf<PendingPurchaseRecord>()

    fun restore(restored: Collection<PendingPurchaseRecord>) = synchronized(lock) {
        records.clear()
        records.addAll(restored.distinctBy { it.identityKey() })
    }

    fun register(record: PendingPurchaseRecord) = synchronized(lock) {
        records.removeAll { it.identityKey() == record.identityKey() }
        records += record
    }

    fun findMatching(
        productIds: Collection<String>,
        orderId: String?,
        purchaseToken: String?,
    ): PendingPurchaseRecord? = synchronized(lock) {
        findMatchingLocked(productIds, orderId, purchaseToken)
    }

    fun attachPurchaseToken(
        productIds: Collection<String>,
        orderId: String?,
        purchaseToken: String,
    ): PendingPurchaseRecord? = synchronized(lock) {
        val tracked = findMatchingLocked(productIds, orderId, purchaseToken) ?: return@synchronized null
        if (tracked.purchaseToken == purchaseToken) return@synchronized tracked
        tracked.copy(purchaseToken = purchaseToken).also { updated ->
            records.removeAll { it.identityKey() == tracked.identityKey() }
            records += updated
        }
    }

    private fun findMatchingLocked(
        productIds: Collection<String>,
        orderId: String?,
        purchaseToken: String?,
    ): PendingPurchaseRecord? {
        return purchaseToken?.let { token -> records.firstOrNull { it.purchaseToken == token } }
            ?: orderId?.let { id -> records.firstOrNull { it.orderId == id } }
            ?: records.filter { it.productId in productIds }.singleOrNull()
    }

    fun remove(record: PendingPurchaseRecord) = synchronized(lock) {
        records.removeAll { it.identityKey() == record.identityKey() }
    }

    fun snapshot(): List<PendingPurchaseRecord> = synchronized(lock) { records.toList() }

    private fun PendingPurchaseRecord.identityKey(): String = orderId ?: "$productType:$productId"
}
