package com.vexora.core.payment

import com.vexora.core.analytics.*
import com.vexora.core.auth.Session
import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.network.ServiceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

fun interface PaymentOrderFactory { suspend fun create(productId: String, session: Session): String }
fun interface PaymentEventSink { fun record(record: PaymentRecord, type: String, now: Long, error: String?) }
data class PaymentViewState(val epoch: String? = null, val record: PaymentRecord? = null,
    val checkoutVisible: Boolean = false, val busy: Boolean = false, val storageFailed: Boolean = false)

/** 所有入口、查单、关闭和成功确认串行处理；持久记录先于任何付款动作。 */
@Singleton
class PaymentEngine @Inject constructor(private val storage: PaymentStorage, private val repository: PaymentRepository,
    private val orders: PaymentOrderFactory, private val sessions: SessionCoordinator, private val events: PaymentEventSink,
    private val clock: PaymentClock, private val config: PaymentConfiguration, private val tracker: EventTracker = NoOpEventTracker) {
    private val lock = Mutex()
    private var records = emptyList<PaymentRecord>()
    private var loaded = false
    private val mutable = MutableStateFlow(PaymentViewState())
    val state = mutable.asStateFlow()
    // 一次性提示不持久化、不重放；只携带所属会话，不把技术错误传给界面。
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val failureEvents = failures.asSharedFlow()
    private fun notifyFailure(session: Session) {
        if (sessions.current?.epoch == session.epoch) failures.tryEmit(session.epoch)
    }
    private fun owner(session: Session) { if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded }
    private suspend fun load() {
        if (loaded) return
        val saved = storage.read()
        // 旧版本把初始化 403 留作未决重试；保留记录，仅停止恢复已明确被拒绝的旧单。
        val migrated = saved.map { record ->
            if (record.stage == PaymentStage.INITIALIZING && record.initialized == null &&
                record.orderId != null && record.error == "payment_order_forbidden") record.copy(stage = PaymentStage.ACCESS_DENIED)
            else record
        }
        if (migrated != saved) storage.write(migrated)
        records = migrated
        loaded = true
    }
    private suspend fun save(record: PaymentRecord, session: Session, visible: Boolean = mutable.value.checkoutVisible, publish: Boolean = true) {
        val updated = records.filterNot { it.key == record.key } + record
        storage.write(updated)
        records = updated
        owner(session)
        if (publish) mutable.value = PaymentViewState(session.epoch, record, visible)
    }
    private fun current(key: String, session: Session) = records.firstOrNull { it.key == key && it.userId == session.userId }
    private fun event(record: PaymentRecord, name: String, error: String? = null) = events.record(record, name, clock.now(), error)
    private suspend fun guarded(action: suspend (Session) -> Unit) = lock.withLock {
        val session = sessions.current ?: return@withLock
        try { load(); action(session) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ServiceFailure.Superseded) { mutable.value = PaymentViewState() }
        catch (_: Exception) {
            if (sessions.current?.epoch == session.epoch) mutable.value = mutable.value.copy(epoch = session.epoch, busy = false, checkoutVisible = false, storageFailed = true)
            notifyFailure(session)
        }
    }
    suspend fun restore() {
        // 账号变化立即清空显示；等待旧请求退出后才读取所属账号记录。
        mutable.value = PaymentViewState()
        guarded { session ->
            val saved = records.lastOrNull { it.userId == session.userId && ((it.stage == PaymentStage.SUCCESS && !it.successAcknowledged) || it.unresolved) }
            if (saved != null) {
                val restored = when (saved.stage) {
                    PaymentStage.CREATING -> saved.copy(stage = PaymentStage.UNCERTAIN, error = "order_unknown")
                    PaymentStage.OFFICIAL_READY -> saved.copy(stage = PaymentStage.CLOSED)
                    else -> saved
                }
                save(restored.copy(source = "restored"), session, false)
            } else mutable.value = PaymentViewState(session.epoch)
        }
    }
    suspend fun buy(productId: String, source: String, product: com.vexora.core.wallet.CoinProduct? = null) = guarded { session ->
        if (config.baseUrl.isBlank()) {
            mutable.value = PaymentViewState(session.epoch, PaymentRecord(UUID.randomUUID().toString(), session.userId, productId, source, stage = PaymentStage.FAILED, error = "unavailable"))
            notifyFailure(session)
            return@guarded
        }
        val old = records.lastOrNull { it.userId == session.userId && it.productId == productId && it.unresolved }
        if (old != null) { resume(old.copy(source = source), session); return@guarded }
        var record = PaymentRecord(UUID.randomUUID().toString(), session.userId, productId, source,
            quotePrice = product?.displayPrice, quoteCurrency = product?.currency)
        save(record, session, false)
        mutable.value = mutable.value.copy(busy = true)
        try {
            val orderId = orders.create(productId, session)
            if (orderId.isBlank()) throw PaymentFailure.InvalidResponse
            record = record.copy(orderId = orderId, stage = PaymentStage.INITIALIZING)
            save(record, session, false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            // 建单超时无 ID 时无法安全查回，保留未决占位，不能直接重建。
            save(record.copy(stage = PaymentStage.UNCERTAIN, error = "order_unknown"), session, false)
            notifyFailure(session)
            reportPayment(record, "unknown", "order_creation", "order_unknown")
            return@guarded
        }
        initialize(record, session)
    }
    private suspend fun initialize(record: PaymentRecord, session: Session) {
        val orderId = record.orderId ?: return
        mutable.value = PaymentViewState(session.epoch, record, busy = true)
        try {
            val init = repository.initialize(orderId, session).validate(orderId)
            owner(session)
            val ready = record.copy(initialized = init, stage = if (init.channelType == "official") PaymentStage.OFFICIAL_READY else PaymentStage.CHECKOUT, error = null)
            save(ready, session, init.channelType == "third_party")
            if (ready.thirdParty) event(ready, "link_ok")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            val terminal = (failure as? PaymentFailure.Http)?.cannotInitialize == true
            val error = when ((failure as? PaymentFailure.Http)?.status) {
                401 -> "payment_auth_failed"
                403 -> "payment_order_forbidden"
                503 -> if (failure.reason == "PAYMENT_CHANNEL_UNAVAILABLE") "payment_channel_unavailable" else "initialize_failed"
                else -> "initialize_failed"
            }
            val stage = when {
                (failure as? PaymentFailure.Http)?.status == 403 -> PaymentStage.ACCESS_DENIED
                terminal -> PaymentStage.FAILED
                else -> PaymentStage.INITIALIZING
            }
            save(record.copy(stage = stage, error = error), session, false)
            notifyFailure(session)
            event(record, "link_error", error)
            reportPayment(record, if (terminal || stage == PaymentStage.ACCESS_DENIED) "failed" else "unknown", "initialize", error)
        }
    }
    private suspend fun resume(record: PaymentRecord, session: Session) {
        if (record.stage == PaymentStage.ACCESS_DENIED) { notifyFailure(session); return }
        mutable.value = PaymentViewState(session.epoch, record, busy = true)
        when {
            record.orderId == null -> {
                save(record.copy(stage = PaymentStage.UNCERTAIN, error = "order_unknown"), session, false)
                notifyFailure(session)
            }
            record.initialized == null -> initialize(record, session)
            record.stage == PaymentStage.OFFICIAL_READY -> save(record, session, false)
            !record.thirdParty -> save(if (record.stage == PaymentStage.CLOSED) record.copy(stage = PaymentStage.OFFICIAL_READY, error = null) else record, session, false)
            record.openedAt == null -> save(record, session, true)
            else -> {
                val checked = query(record.copy(stage = PaymentStage.CHECKOUT), session, closing = false)
                val urlValid = (paymentTime(checked.initialized?.expiresAt) ?: 0) > clock.now()
                if (checked.stage == PaymentStage.CHECKOUT && !checked.expired(clock.now()) && urlValid) save(checked, session, true)
            }
        }
    }
    suspend fun retry(key: String) = guarded { session -> current(key, session)?.let { resume(it, session) } }
    suspend fun opened(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (!record.thirdParty || !record.unresolved) return@guarded
        val now = clock.now()
        if ((paymentTime(record.initialized?.expiresAt) ?: 0) <= now || record.expired(now)) {
            save(record.copy(stage = PaymentStage.TIMED_OUT, error = "expired_link"), session, false)
            event(record, "link_error", "expired_link")
            notifyFailure(session)
            return@guarded
        }
        if (record.openedAt != null) return@guarded
        val opened = record.copy(openedAt = now, deadline = now + config.duration(record.initialized?.maxQuerySeconds) * 1000L, stage = PaymentStage.CHECKOUT)
        save(opened, session, true)
        tracker.track("initiate_pay", paymentValues(opened, "initiated", "checkout_open"), session.userId)
        event(opened, "page_opened")
    }
    suspend fun pageEvent(key: String, type: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (!record.thirdParty || !record.unresolved) return@guarded
        if (type in setOf("page_loaded", "page_load_error")) event(record, type)
        if (type == "page_load_error") notifyFailure(session)
    }
    suspend fun poll(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.thirdParty && record.orderId != null && record.unresolved) query(record, session, closing = false)
    }
    private suspend fun query(record: PaymentRecord, session: Session, closing: Boolean, publish: Boolean = true): PaymentRecord {
        val visible = publish && mutable.value.checkoutVisible
        if (publish) mutable.value = PaymentViewState(session.epoch, record, visible, busy = closing)
        var next = record
        var serverStatus: String? = null
        try {
            val response = repository.status(record.orderId!!, session)
            owner(session)
            if (response.orderId != record.orderId) throw PaymentFailure.InvalidResponse
            serverStatus = response.status
            next = when {
                response.successful -> record.copy(stage = PaymentStage.SUCCESS, error = null)
                response.terminalFailure -> record.copy(stage = PaymentStage.FAILED, error = "payment_failed")
                response.status == "paid" -> record.copy(stage = PaymentStage.AWAITING_FULFILLMENT, error = null)
                response.status == "pending" -> record.copy(stage = PaymentStage.CHECKOUT, error = null)
                else -> record.copy(stage = PaymentStage.UNCERTAIN, error = "verification_failed")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { owner(session); next = record.copy(stage = PaymentStage.UNCERTAIN, error = "verification_failed") }
        if (next.stage == PaymentStage.SUCCESS) {
            save(next, session, false, publish)
            event(next, if (closing) "paid_on_close" else "paid_while_open")
            reportPayment(next, "success", "fulfilled", once = "payment:${next.orderId}:fulfilled")
            return next
        }
        val timeout = next.unresolved && next.expired(clock.now())
        if (closing) {
            event(record, "user_cancel")
            if (next.unresolved) reportPayment(record, "closed", "checkout_close")
            if (next.unresolved) next = next.copy(stage = PaymentStage.CLOSED)
        } else if (timeout) {
            if (record.stage != PaymentStage.TIMED_OUT) {
                event(record, "poll_timeout")
                reportPayment(record, "unknown", "poll_timeout", "poll_timeout")
                if (visible && record.initialized?.openMode == "webview") event(record, "timeout_cancel")
            }
            next = next.copy(stage = PaymentStage.TIMED_OUT)
        }
        if (next.stage == PaymentStage.FAILED && record.stage != PaymentStage.FAILED)
            reportPayment(next, if (serverStatus == "cancelled") "cancelled" else "failed", "server_status",
                when (serverStatus) { "cancelled" -> "server_cancelled"; "expired" -> "payment_expired"; else -> "payment_failed" }, "payment:${next.orderId}:failed")
        if (next.stage == PaymentStage.AWAITING_FULFILLMENT && record.stage != next.stage)
            reportPayment(next, "pending", "awaiting_fulfillment", once = "payment:${next.orderId}:awaiting_fulfillment")
        if (!closing && next.unresolved && record.stage in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT)) next = next.copy(stage = record.stage)
        save(next, session, visible && !closing && !timeout && next.stage != PaymentStage.FAILED, publish)
        if (publish && next.stage == PaymentStage.FAILED && record.stage != PaymentStage.FAILED) notifyFailure(session)
        return next
    }
    suspend fun close(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.thirdParty && record.unresolved && record.orderId != null) query(record, session, closing = true)
        else mutable.value = mutable.value.copy(checkoutVisible = false)
        presentWaitingSuccess(session)
    }
    private suspend fun presentWaitingSuccess(session: Session) {
        if (mutable.value.checkoutVisible || mutable.value.record?.stage == PaymentStage.SUCCESS) return
        records.firstOrNull { it.userId == session.userId && it.stage == PaymentStage.SUCCESS && !it.successAcknowledged }?.let {
            // 延迟通知只补展示结果，不重放旧订单的来源导航。
            save(it.copy(source = "restored"), session, false)
        }
    }
    suspend fun claimOfficial(key: String): PaymentRecord? {
        var result: PaymentRecord? = null
        guarded { session ->
            val record = current(key, session)?.takeIf { it.stage == PaymentStage.OFFICIAL_READY } ?: return@guarded
            val launched = record.copy(stage = PaymentStage.OFFICIAL_LAUNCHED)
            save(launched, session, false)
            result = launched
        }
        return result
    }
    suspend fun officialResult(key: String, cancelledOrUnavailable: Boolean, consumed: Boolean = false, failed: Boolean = false) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.stage == PaymentStage.SUCCESS) return@guarded
        // 不把 consume 完成当作已发币，继续等真实充值通知。
        save(record.copy(stage = if (cancelledOrUnavailable) PaymentStage.CLOSED else if (consumed) PaymentStage.OFFICIAL_CONSUMED else PaymentStage.OFFICIAL_LAUNCHED), session, false)
        if (failed) notifyFailure(session)
        if (cancelledOrUnavailable || consumed) presentWaitingSuccess(session)
    }
    suspend fun recharge(orderId: String?): Boolean {
        var handled = true
        guarded { session ->
            val matching = records.lastOrNull { it.userId == session.userId && it.orderId == orderId && orderId != null }
            if (matching != null) {
                handled = true
                if (matching.stage == PaymentStage.SUCCESS) return@guarded
                val present = mutable.value.record == null || mutable.value.record?.key == matching.key
                if (matching.thirdParty) query(matching, session, false, present)
                else if (matching.initialized?.channelType == "official") save(matching.copy(stage = PaymentStage.SUCCESS), session, false, present)
            } else {
                val pending = records.filter { it.userId == session.userId && it.thirdParty }
                handled = records.any { it.userId == session.userId }
                pending.filter { it.unresolved && it.orderId != null }.forEach { query(it, session, false, mutable.value.record == null || mutable.value.record?.key == it.key) }
            }
        }
        return handled
    }
    private fun paymentValues(record: PaymentRecord, status: String, stage: String, reason: String? = null): Map<String, Any> =
        paymentProperties(status, record.initialized?.channelCode ?: "unknown", "SERVICE", record.source, record.productId,
            record.orderId, stage, reason) + paymentPriceProperties(record.quotePrice, record.quoteCurrency, "catalog_quote")
        // 查单接口没有实付金额，报价仅用于 af_price，不计入 af_revenue。
    private fun reportPayment(record: PaymentRecord, status: String, stage: String, reason: String? = null, once: String? = null) {
        tracker.paymentResult(paymentValues(record, status, stage, reason), record.userId, once)
    }
    suspend fun shown(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.stage == PaymentStage.SUCCESS && record.successShownAt == null) save(record.copy(successShownAt = clock.now()), session, false)
    }
    suspend fun acknowledge(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.stage == PaymentStage.SUCCESS) {
            save(record.copy(successAcknowledged = true), session, false)
            records.firstOrNull { it.userId == session.userId && it.stage == PaymentStage.SUCCESS && !it.successAcknowledged }?.let {
                mutable.value = PaymentViewState(session.epoch, it.copy(source = "restored"))
            }
        }
    }
}
