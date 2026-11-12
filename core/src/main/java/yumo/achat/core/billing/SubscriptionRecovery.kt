package yumo.achat.core.billing

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Coalesce only active work. Exhausted recovery remains eligible for the next foreground signal. */
internal class SubscriptionRecoveryWorker(
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 3000,
    private val recover: suspend () -> Boolean,
) {
    private var job: Job? = null
    @Synchronized fun request(): Job {
        job?.takeIf { !it.isCompleted }?.let { return it }
        return scope.launch(start = CoroutineStart.LAZY) {
            repeat(3) { attempt ->
                val done = try { recover() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (done) return@launch
                if (attempt < 2) delay(retryDelayMs * (attempt + 1))
            }
        }.also { job = it; it.start() }
    }
}

@Serializable
internal data class SubscriptionPurchaseSnapshot(
    val purchaseId: String, val owner: String, val orderId: String, val sku: String,
    val trigger: String?, val isVip: Boolean?, val priceMicros: Long?, val currency: String?,
    val successClaimed: Boolean = false, val token: String? = null, val playOrderId: String? = null,
) { override fun toString() = "SubscriptionPurchaseSnapshot(identity hidden)" }

/** Independent of pending records; prepare never replaces the original quote or ownership. */
internal class SubscriptionPurchaseLedger(
    private val json: Json,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    private fun key(owner: String, order: String) = "ledger:${owner.length}:$owner:$order"
    @Synchronized fun prepare(snapshot: SubscriptionPurchaseSnapshot) {
        val key = key(snapshot.owner, snapshot.orderId)
        if (read(key) == null) write(key, json.encodeToString(snapshot))
    }
    @Synchronized fun claim(owner: String, order: String, token: String, playOrderId: String?): SubscriptionPurchaseSnapshot? {
        val key = key(owner, order)
        val saved = read(key)?.let { json.decodeFromString<SubscriptionPurchaseSnapshot>(it) } ?: return null
        check(saved.owner == owner && saved.orderId == order)
        if (saved.successClaimed || (saved.token != null && saved.token != token)) return null
        write(key, json.encodeToString(saved.copy(successClaimed = true, token = token, playOrderId = playOrderId)))
        return saved.copy(playOrderId = playOrderId)
    }
}

/** Play confirmation, statistics and acknowledgement do not depend on backend entitlement polling. */
internal suspend fun handlePurchasedSubscription(
    state: PurchaseState, report: suspend () -> Unit, acknowledge: suspend () -> Boolean,
): Boolean {
    if (state != PurchaseState.PURCHASED) return false
    return withContext(NonCancellable) {
        try { report() } catch (_: Exception) { /* Statistics cannot prevent ACK or entitlement reconciliation. */ }
        try { acknowledge() } catch (_: Exception) { false }
    }
}

internal fun subscriptionPurchaseProperties(snapshot: SubscriptionPurchaseSnapshot, productId: String): Map<String, Any> {
    val currency = snapshot.currency?.takeIf { code ->
        code.matches(Regex("[A-Z]{3}")) && runCatching { java.util.Currency.getInstance(code) }.isSuccess
    }
    val quoted = snapshot.priceMicros != null && snapshot.priceMicros >= 0 && currency != null
    return yumo.achat.core.analytics.paymentProperties("success", "google_play", "subscription",
        snapshot.trigger ?: "subscription", productId, snapshot.orderId, "play_purchased",
        amount = snapshot.priceMicros?.takeIf { quoted }?.toDouble()?.div(1_000_000),
        currency = currency.takeIf { quoted }) + buildMap<String, Any> {
        put("purchase_id", snapshot.purchaseId); put("order_id", snapshot.orderId)
        put("goods_id", snapshot.sku); put("af_content_id", snapshot.sku); put("type", "subscription")
        snapshot.playOrderId?.let { put("play_order_id", it) }
        snapshot.isVip?.let { put("is_vip", it) }
    }
}

/** BillingManager already completed the Play request. Local cancellation never waits for the network. */
internal suspend fun finishCancelledSubscription(
    clearPending: suspend () -> Unit, release: () -> Unit, publish: () -> Unit,
) = withContext(NonCancellable) {
    try { clearPending() } finally { release() }
    publish()
}
