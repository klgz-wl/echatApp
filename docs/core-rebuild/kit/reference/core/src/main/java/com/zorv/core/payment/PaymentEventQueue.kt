package com.zorv.core.payment

import android.content.Context
import com.zorv.core.analytics.*
import com.zorv.core.auth.SessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable private data class QueuedPaymentEvent(val key: String, val userId: String, val orderId: String,
    val event: PaymentClientEvent, val attempts: Int = 0)

/** 可选遥测与交易解耦。仅记录协议字段，不收集 URL、HTML 或表单内容。 */
@Singleton
class PaymentEventQueue @Inject constructor(@ApplicationContext context: Context, private val config: PaymentConfiguration,
    private val repository: PaymentRepository, private val sessions: SessionCoordinator, private val json: Json, private val tracker: EventTracker) : PaymentEventSink {
    private val preferences = context.getSharedPreferences(config.storageName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        scope.launch { sessions.state.map { it?.epoch }.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch {
            for (signal in wake) {
                val session = sessions.current ?: continue
                while (currentCoroutineContext().isActive && sessions.current?.epoch == session.epoch) {
                    val queued = lock.withLock { read().firstOrNull { it.userId == session.userId && it.attempts < config.eventAttempts } } ?: break
                    var permanent = false
                    try { repository.event(queued.orderId, queued.event, session); permanent = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    // Repository 的 401 已经过一次令牌刷新；遥测不能再次排队刷新同一认证失败。
                    catch (failure: PaymentFailure.Http) { permanent = failure.status in setOf(400, 401, 403, 404, 413) }
                    catch (_: Exception) { /* 同一事件有界重试，不能阻塞订单查询。 */ }
                    lock.withLock {
                        save(read().mapNotNull { if (it.key != queued.key) it else if (permanent) null else it.copy(attempts = it.attempts + 1) })
                    }
                    delay(config.eventRetryMs)
                }
            }
        }
    }
    override fun record(record: PaymentRecord, type: String, now: Long, error: String?) {
        val name = when (type) { "link_ok" -> "3rdpayment_link_ok"; "link_error" -> "3rdpayment_link_error"; "page_loaded" -> "3rdpayment_page_loaded"; else -> null }
        if (name != null) tracker.track(name, buildMap {
            put("package_id", record.productId); put("entry_source", record.source); put("af_order_id", record.orderId.orEmpty())
            put("channel_code", record.initialized?.channelCode.orEmpty()); put("open_mode", record.initialized?.openMode.orEmpty())
            put("payment_flow", "SERVICE"); put("page_name", "recharge_paywall")
            record.openedAt?.let { put("duration", (now - it).coerceAtLeast(0)) }; error?.let { put("fail_reason", it) }
        }, record.userId)
        val order = record.orderId ?: return
        scope.launch {
            try {
                val init = record.initialized
                val event = PaymentClientEvent(type, init?.channelCode, init?.openMode,
                    occurredAt = paymentTimestamp(now), openedAt = record.openedAt?.let(::paymentTimestamp),
                    closedAt = now.takeIf { type in setOf("user_cancel", "paid_on_close", "timeout_cancel") }?.let(::paymentTimestamp),
                    durationMs = record.openedAt?.let { (now - it).coerceAtLeast(0) }, errorCode = error)
                val key = "${record.key}:$type:$now"
                lock.withLock {
                    val queued = read().filter { it.attempts < config.eventAttempts }
                    save((queued + QueuedPaymentEvent(key, record.userId, order, event)).distinctBy { it.key }.takeLast(config.eventLimit))
                }
                wake.trySend(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 遥测存储失败不能改变支付结果。 */ }
        }
    }
    private fun read(): List<QueuedPaymentEvent> = runCatching {
        preferences.getString("events", null)?.let { json.decodeFromString<List<QueuedPaymentEvent>>(it) } ?: emptyList()
    }.getOrDefault(emptyList())
    private fun save(value: List<QueuedPaymentEvent>) { preferences.edit().putString("events", json.encodeToString(value)).commit() }
}
