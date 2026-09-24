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
    private val wallet: WalletRepository, val configuration: PaymentConfiguration) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = engine.state
    private val foreground = MutableStateFlow(false)
    init {
        scope.launch { sessions.state.map { it?.epoch }.distinctUntilChanged().collectLatest { engine.restore() } }
        scope.launch {
            foreground.collectLatest { active ->
                if (!active) return@collectLatest
                var observedKey: String? = null
                while (currentCoroutineContext().isActive) {
                    val snapshot = state.value
                    val record = snapshot.record
                    val pollable = record?.thirdParty == true || record?.stage in setOf(
                        PaymentStage.OFFICIAL_LAUNCHED,
                        PaymentStage.AWAITING_FULFILLMENT,
                    )
                    if (snapshot.epoch == sessions.current?.epoch && pollable && record?.orderId != null && record.unresolved &&
                        record.openedAt != null && (record.key != observedKey || record.stage !in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT))) engine.poll(record.key)
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
            sessions.synchronize()
            foreground.value = true
        }
    }
    fun retry(key: String) { scope.launch { engine.retry(key) } }
    fun close(key: String) { scope.launch { engine.close(key) } }
    fun opened(key: String) { scope.launch { engine.opened(key) } }
    fun poll(key: String) { scope.launch { engine.poll(key) } }
    fun shown(key: String) { scope.launch { engine.shown(key) } }
    fun acknowledge(key: String) { scope.launch { engine.acknowledge(key) } }
    fun pageEvent(key: String, type: String) { scope.launch { engine.pageEvent(key, type) } }
    suspend fun refreshWallet() {
        try { wallet.refreshBalance(); wallet.refreshProducts(); wallet.loadRecords(refresh = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 不改写支付成功；页面支持再次刷新。 */ }
    }
}
