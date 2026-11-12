package yumo.achat.core.payment

import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.wallet.WalletRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** 生命周期只控制查询调度，交易记录独立于 Activity 与页面。 */
@Singleton
class PaymentCoordinator @Inject constructor(val engine: PaymentEngine, private val sessions: SessionCoordinator,
    private val wallet: WalletRepository, val configuration: PaymentConfiguration,
    private val billing: yumo.achat.core.billing.BillingManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = engine.state
    private val foreground = MutableStateFlow(false)
    init {
        scope.launch {
            state.map { snapshot -> snapshot.record?.takeIf { it.thirdParty && it.unresolved }
                ?.let { Triple(snapshot.epoch, it.key, it.checkoutDeadline) } }.distinctUntilChanged().collectLatest { timer ->
                val (epoch, key, deadline) = timer ?: return@collectLatest
                if (deadline == null) return@collectLatest
                delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
                if (sessions.current?.epoch == epoch) engine.expireCheckout(key)
            }
        }
        scope.launch {
            combine(foreground, state) { active, snapshot ->
                snapshot.takeIf { active && it.record?.stage == PaymentStage.CLOSED &&
                    it.record.initialized?.channelType == "official" && !it.record.locallyReleased }
                    ?.let { Triple(it.epoch, it.record!!.key, it.record.initialized?.sdkParams?.productId) }
            }.distinctUntilChanged().collectLatest { candidate ->
                val (epoch, key, sku) = candidate ?: return@collectLatest
                if (sku.isNullOrBlank()) return@collectLatest
                repeat(3) { attempt ->
                    if (sessions.current?.epoch != epoch) return@collectLatest
                    val queried = try {
                        if (!billing.connect().isSuccess) null else billing.queryPurchases(
                            yumo.achat.core.billing.BillingProductType.IN_APP, emitRecoveryEvents = false)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (sessions.current?.epoch != epoch) return@collectLatest
                    if (queried?.isSuccess == true) {
                        val owned = queried.data.orEmpty().any { it.productId == sku && it.purchaseState in setOf(
                            yumo.achat.core.billing.PurchaseState.PENDING, yumo.achat.core.billing.PurchaseState.PURCHASED) }
                        engine.releaseClosedOfficial(key, owned, true)
                        return@collectLatest
                    }
                    if (attempt < 2) delay(1500L * (attempt + 1))
                }
            }
        }
        scope.launch { sessions.state.map { it?.epoch }.distinctUntilChanged().collectLatest { engine.restore() } }
        scope.launch {
            foreground.collectLatest { active ->
                if (!active) return@collectLatest
                var observedKey: String? = null
                while (currentCoroutineContext().isActive) {
                    val snapshot = state.value
                    val record = snapshot.record
                    val pollable = shouldAutoPollPayment(record)
                    if (snapshot.epoch == sessions.current?.epoch && pollable && record?.orderId != null && record.unresolved &&
                        (record.openedAt != null || record.stage == PaymentStage.AWAITING_FULFILLMENT) && (record.key != observedKey || record.stage !in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT))) engine.poll(record.key)
                    observedKey = record?.key
                    val current = state.value.record
                    val interval = configuration.interval(current?.initialized?.queryIntervalSeconds) * 1000L
                    val remaining = current?.deadline?.minus(System.currentTimeMillis())
                    delay(if (remaining != null && remaining > 0) minOf(interval, remaining) else interval)
                }
            }
        }
        scope.launch {
            state.map { it.epoch to it.record?.takeIf { record -> record.stage == PaymentStage.SUCCESS }?.key }
                .distinctUntilChanged().collectLatest { (epoch, key) ->
                    if (key != null && sessions.current?.epoch == epoch) refreshWallet()
                }
        }
    }
    fun foreground(active: Boolean) {
        if (!active) {
            foreground.value = false
            return
        }
        scope.launch {
            state.value.record?.let { engine.expireCheckout(it.key) }
            sessions.synchronize()
            foreground.value = true
        }
    }
    fun retry(key: String) { scope.launch { engine.retry(key) } }
    fun close(key: String) { scope.launch { engine.close(key) } }
    fun opened(key: String) { scope.launch { engine.opened(key) } }
    fun poll(key: String) { scope.launch { engine.poll(key) } }
    fun expireCheckout(key: String) { scope.launch { engine.expireCheckout(key) } }
    fun shown(key: String) { scope.launch { engine.shown(key) } }
    fun acknowledge(key: String) { scope.launch { engine.acknowledge(key) } }
    fun pageEvent(key: String, type: String, details: PaymentClientEventDetails? = null) {
        scope.launch { engine.pageEvent(key, type, details) }
    }
    suspend fun refreshWallet() {
        try { wallet.refreshBalance(); wallet.refreshProducts(); wallet.loadRecords(refresh = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 不改写支付成功；页面支持再次刷新。 */ }
    }
}

internal fun shouldAutoPollPayment(record: PaymentRecord?): Boolean {
    if (record == null || record.productType == "membership") return false
    if (record.stage == PaymentStage.AWAITING_FULFILLMENT) return true
    if (record.thirdParty) return record.initialized?.openMode != "external_browser"
    return record.stage in setOf(
        PaymentStage.OFFICIAL_LAUNCHED,
        PaymentStage.AWAITING_FULFILLMENT,
        PaymentStage.UNCERTAIN,
    ) && record.initialized?.channelType == "official"
}
