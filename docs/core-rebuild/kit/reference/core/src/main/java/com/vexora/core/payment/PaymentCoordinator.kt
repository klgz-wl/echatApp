package com.vexora.core.payment

import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.wallet.WalletRepository
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
                var first = true
                while (currentCoroutineContext().isActive) {
                    val snapshot = state.value
                    val record = snapshot.record
                    if (snapshot.epoch == sessions.current?.epoch && record?.thirdParty == true && record.orderId != null && record.unresolved &&
                        record.openedAt != null && (first || record.stage !in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT))) engine.poll(record.key)
                    first = false
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
    fun foreground(active: Boolean) { foreground.value = active }
    fun retry(key: String) { scope.launch { engine.retry(key) } }
    fun close(key: String) { scope.launch { engine.close(key) } }
    fun pageEvent(key: String, type: String) { scope.launch { engine.pageEvent(key, type) } }
    suspend fun refreshWallet() {
        try { wallet.refreshBalance(); wallet.refreshProducts(); wallet.loadRecords(refresh = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 不改写支付成功；页面支持再次刷新。 */ }
    }
}
