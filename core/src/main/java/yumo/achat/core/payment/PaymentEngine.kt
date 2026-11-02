package yumo.achat.core.payment

import yumo.achat.core.analytics.*
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ServiceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PaymentBusinessOrder(
    val orderId: String,
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val status: String = "pending",
)
fun interface PaymentOrderFactory { suspend fun create(productId: String, requestId: String, session: Session): PaymentBusinessOrder }
data class PaymentClientEventDetails(
    val page: String = "recharge_paywall",
    val url: String? = null,
    val errorMessage: String? = null,
    val htmlExcerpt: String? = null,
)
fun interface PaymentEventSink { fun record(record: PaymentRecord, type: String, now: Long, error: String?, details: PaymentClientEventDetails?) }
data class PaymentViewState(val epoch: String? = null, val record: PaymentRecord? = null,
    val checkoutVisible: Boolean = false, val busy: Boolean = false, val storageFailed: Boolean = false,
    val recoveryLookupFailed: Boolean = false, val recoveryLookupError: String? = null)

/** 所有入口、查单、关闭和成功确认串行处理；持久记录先于任何付款动作。 */
@Singleton
class PaymentEngine @Inject constructor(private val storage: PaymentStorage, private val repository: PaymentRepository,
    private val orders: PaymentOrderFactory, private val sessions: SessionCoordinator, private val events: PaymentEventSink,
    private val clock: PaymentClock, private val config: PaymentConfiguration, private val tracker: EventTracker = NoOpEventTracker) {
    private val lock = Mutex()
    private var records = emptyList<PaymentRecord>()
    private var loaded = false
    private val dismissals = java.util.concurrent.ConcurrentHashMap<String, PaymentStage>()
    private val mutable = MutableStateFlow(PaymentViewState())
    val state = mutable.asStateFlow()
    // 一次性提示不持久化、不重放；只携带所属会话，不把技术错误传给界面。
    private val failures = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val failureEvents = failures.asSharedFlow()
    private fun notifyFailure(session: Session) {
        if (sessions.current?.epoch == session.epoch) failures.tryEmit(session.epoch)
    }
    private fun owner(session: Session) { if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded }
    private fun safeError(failure: Exception, fallback: String) = if (failure is java.io.IOException) "network_error" else fallback
    private suspend fun load() {
        if (loaded) return
        val saved = storage.read()
        // 旧版本把初始化 403 留作未决重试；保留记录，仅停止恢复已明确被拒绝的旧单。
        val migrated = saved.map { record ->
            if (record.stage == PaymentStage.INITIALIZING && record.initialized == null &&
                record.orderId != null && record.error == "payment_order_forbidden") record.copy(stage = PaymentStage.ACCESS_DENIED)
            else if (record.stage == PaymentStage.TIMED_OUT && record.initialized?.channelType == "official") {
                record.copy(stage = PaymentStage.UNCERTAIN, error = null)
            }
            else record
        }
        if (migrated != saved) storage.write(migrated)
        records = migrated
        loaded = true
    }
    private suspend fun save(record: PaymentRecord, session: Session, visible: Boolean = mutable.value.checkoutVisible, publish: Boolean = true) {
        var saved = dismissedRecord(record)
        var updated = records.filterNot { it.key == record.key } + saved
        storage.write(updated)
        records = updated
        owner(session)
        // Close/expiry may arrive while the storage commit is suspended.
        val afterCommit = dismissedRecord(saved)
        if (afterCommit != saved) {
            saved = afterCommit
            updated = records.filterNot { it.key == saved.key } + saved
            storage.write(updated)
            records = updated
            owner(session)
        }
        val display = dismissedRecord(saved)
        if (publish) mutable.value = PaymentViewState(session.epoch, display,
            visible && !(display.thirdParty && (display.expired(clock.now()) || display.stage in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT))))
    }
    private fun dismissedRecord(record: PaymentRecord): PaymentRecord {
        if (!record.thirdParty || !record.unresolved || record.stage == PaymentStage.AWAITING_FULFILLMENT) return record
        val stage = if (record.expired(clock.now())) PaymentStage.TIMED_OUT else dismissals[record.key] ?: return record
        return record.copy(stage = stage, error = if (stage == PaymentStage.TIMED_OUT) "timeout" else record.error)
    }
    private fun current(key: String, session: Session) = records.firstOrNull { it.key == key && it.userId == session.userId }
    private fun event(record: PaymentRecord, name: String, error: String? = null, details: PaymentClientEventDetails? = null) =
        events.record(record, name, clock.now(), error, details)
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
                val recovered = if (restored.thirdParty && restored.unresolved && restored.stage != PaymentStage.AWAITING_FULFILLMENT && restored.expired(clock.now()))
                    restored.copy(stage = PaymentStage.TIMED_OUT, error = "timeout") else restored
                save(recovered.copy(source = "restored"), session, false)
            } else mutable.value = PaymentViewState(session.epoch)
        }
    }
    suspend fun buy(productId: String, source: String, product: yumo.achat.core.wallet.CoinProduct? = null) = guarded { session ->
        if (config.baseUrl.isBlank()) {
            mutable.value = PaymentViewState(session.epoch, PaymentRecord(UUID.randomUUID().toString(), session.userId, productId, source, stage = PaymentStage.FAILED, error = "unavailable"))
            notifyFailure(session)
            return@guarded
        }
        val old = records.lastOrNull { it.userId == session.userId && it.unresolved }
        if (old != null) {
            if (old.recoveryRequired) save(old, session, false) else resume(old.copy(source = source), session)
            return@guarded
        }
        mutable.value = PaymentViewState(session.epoch, busy = true)
        val recovered = try {
            repository.recover(productId, session)?.validate(session.userId).also { owner(session) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            val issue = when {
                failure is PaymentFailure.RecoveryRequiresReview ||
                    (failure is PaymentFailure.Http && failure.status == 409 && failure.reason == "PAYMENT_RECOVERY_REQUIRES_REVIEW") -> "payment_review_required"
                failure is java.io.IOException -> "network_error"
                else -> "recovery_lookup_failed"
            }
            // A failed lookup is not a lost create: leave durable intents untouched so every retry looks up again.
            mutable.value = PaymentViewState(session.epoch, recoveryLookupFailed = true, recoveryLookupError = issue)
            notifyFailure(session)
            return@guarded
        }
        if (recovered != null) {
            val official = recovered.paymentMethod == "google_play"
            val record = PaymentRecord(UUID.randomUUID().toString(), session.userId, recovered.productId, "restored",
                orderId = recovered.orderId,
                stage = if (recovered.status == "paid") PaymentStage.AWAITING_FULFILLMENT else PaymentStage.UNCERTAIN,
                initialized = if (official) InitializedPayment(recovered.orderId, "official", "google_play", "sdk",
                    PaymentSdkParams(recovered.providerProductId)) else null,
                quotePrice = recovered.amount, quoteCurrency = recovered.currency, quoteSource = "server_snapshot",
                obfuscatedAccountId = recovered.obfuscatedAccountId, obfuscatedProfileId = recovered.obfuscatedProfileId,
                officialFallbackProductId = recovered.providerProductId,
                recoveryRequired = true, recoveredPaymentMethod = recovered.paymentMethod, recoveredTrigger = recovered.trigger)
            save(record, session, false)
            return@guarded
        }
        val record = PaymentRecord(UUID.randomUUID().toString(), session.userId, productId, source,
            quotePrice = product?.displayPrice, quoteCurrency = product?.currency,
            officialFallbackProductId = product?.googleProductId?.takeIf { it.isNotBlank() })
        save(record, session, false)
        create(record, session)
    }
    private suspend fun create(intent: PaymentRecord, session: Session) {
        var record = intent
        mutable.value = mutable.value.copy(busy = true)
        try {
            val order = orders.create(record.productId, record.key, session)
            if (order.orderId.isBlank()) throw PaymentFailure.InvalidResponse
            record = record.copy(
                orderId = order.orderId,
                stage = PaymentStage.INITIALIZING,
                obfuscatedAccountId = order.obfuscatedAccountId,
                obfuscatedProfileId = order.obfuscatedProfileId,
                error = null,
            )
            save(record, session, false)
            if (order.status != "pending") { query(record, session, false); return }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            val rejected = record.orderId == null && (failure as? PaymentFailure.Http)?.let {
                it.status == 409 && it.reason == "PAYMENT_PRODUCT_UNAVAILABLE"
            } == true
            save(record.copy(stage = if (rejected) PaymentStage.FAILED else PaymentStage.UNCERTAIN,
                error = if (rejected) "product_unavailable" else safeError(failure, "order_unknown")), session, false)
            notifyFailure(session)
            return
        }
        initialize(record, session)
    }
    private suspend fun initialize(record: PaymentRecord, session: Session) {
        val orderId = record.orderId ?: return
        mutable.value = PaymentViewState(session.epoch, record, busy = true)
        try {
            val init = repository.initialize(orderId, session).validate(orderId)
            owner(session)
            if (init.channelType == "third_party" && (paymentTime(init.expiresAt) ?: 0) <= clock.now()) {
                val expired = record.copy(
                    initialized = init,
                    stage = PaymentStage.TIMED_OUT,
                    error = "timeout",
                )
                save(expired, session, false)
                event(expired, "link_error", "expired_link")
                notifyFailure(session)
                return
            }
            val ready = record.copy(initialized = init, stage = if (init.channelType == "official") PaymentStage.OFFICIAL_READY else PaymentStage.CHECKOUT,
                deadline = if (init.channelType == "third_party") minOf(paymentTime(init.expiresAt)!!,
                    clock.now() + config.duration(init.maxQuerySeconds) * 1000L) else record.deadline, error = null)
            save(ready, session, init.channelType == "third_party")
            if (ready.thirdParty) event(ready, "link_ok")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            val error = safeError(failure, when ((failure as? PaymentFailure.Http)?.status) {
                401 -> "payment_auth_failed"
                403 -> "payment_order_forbidden"
                503 -> if (failure.reason == "PAYMENT_CHANNEL_UNAVAILABLE") "payment_channel_unavailable" else "initialize_failed"
                else -> "initialize_failed"
            })
            val stage = when {
                (failure as? PaymentFailure.Http)?.status == 403 -> PaymentStage.ACCESS_DENIED
                else -> PaymentStage.INITIALIZING
            }
            save(record.copy(stage = stage, error = error), session, false)
            notifyFailure(session)
            event(record, "link_error", error)
            if ((failure as? PaymentFailure.Http)?.cannotInitialize == true) query(record.copy(error = error), session, false)
        }
    }
    private suspend fun resume(record: PaymentRecord, session: Session) {
        if (record.stage == PaymentStage.ACCESS_DENIED) { notifyFailure(session); return }
        mutable.value = PaymentViewState(session.epoch, record, busy = true)
        dismissals.remove(record.key)
        when {
            record.stage == PaymentStage.AWAITING_FULFILLMENT -> query(record, session, closing = false)
            record.orderId == null -> create(record, session)
            record.initialized == null -> initialize(record, session)
            record.stage == PaymentStage.OFFICIAL_READY -> save(record, session, false)
            !record.thirdParty -> if (record.stage == PaymentStage.CLOSED) {
                save(record.copy(stage = PaymentStage.OFFICIAL_READY, error = null), session, false)
            } else {
                query(record, session, closing = false)
            }
            else -> {
                val checked = query(record.copy(stage = PaymentStage.CHECKOUT), session, closing = false)
                val urlValid = (paymentTime(checked.initialized?.expiresAt) ?: 0) > clock.now()
                if (checked.stage == PaymentStage.CHECKOUT && !checked.expired(clock.now()) && urlValid) save(checked, session, true)
            }
        }
    }
    suspend fun retry(key: String) = guarded { session ->
        current(key, session)?.let { saved ->
            val record = if (saved.recoveryRequired) saved.copy(recoveryRequired = false,
                openedAt = if (saved.initialized?.channelType == "official") clock.now() else saved.openedAt) else saved
            save(record, session, false)
            resume(record, session)
        }
    }
    suspend fun opened(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (!record.thirdParty || !record.unresolved) return@guarded
        if (record.stage == PaymentStage.AWAITING_FULFILLMENT) return@guarded
        val now = clock.now()
        if ((paymentTime(record.initialized?.expiresAt) ?: 0) <= now || record.expired(now)) {
            save(record.copy(stage = PaymentStage.TIMED_OUT, error = "timeout"), session, false)
            event(record, "link_error", "expired_link")
            notifyFailure(session)
            return@guarded
        }
        if (record.openedAt != null) {
            save(record.copy(stage = PaymentStage.CHECKOUT), session, true)
            return@guarded
        }
        val opened = record.copy(openedAt = now, deadline = record.checkoutDeadline ?:
            now + config.duration(record.initialized?.maxQuerySeconds) * 1000L, stage = PaymentStage.CHECKOUT)
        save(opened, session, true)
        val values = paymentValues(opened, "initiated", "checkout_open")
        tracker.track("initiate_pay", values + paymentRechargeProperties(values, session.userId), session.userId)
        event(opened, "page_opened")
    }
    suspend fun pageEvent(key: String, type: String, details: PaymentClientEventDetails? = null) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (!record.thirdParty || !record.unresolved) return@guarded
        if (type in setOf("page_loaded", "page_load_error")) event(record, type, details?.errorMessage, details)
        if (type == "page_load_error") {
            save(record.copy(error = "network_error"), session)
            notifyFailure(session)
        } else if (type == "page_loaded" && record.stage == PaymentStage.CHECKOUT && record.error == "network_error" && !record.expired(clock.now())) {
            save(record.copy(error = null), session)
        }
    }
    suspend fun poll(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.orderId != null && record.unresolved &&
            (record.thirdParty || shouldAutoPollPayment(record))
        ) query(record, session, closing = false)
    }
    private suspend fun query(record: PaymentRecord, session: Session, closing: Boolean, publish: Boolean = true): PaymentRecord {
        val visible = publish && mutable.value.checkoutVisible
        if (publish) mutable.value = PaymentViewState(session.epoch, record, visible, busy = closing)
        var next = record
        try {
            val response = if (closing) repository.close(record.orderId!!, session) else repository.status(record.orderId!!, session)
            owner(session)
            if (response.orderId != record.orderId) throw PaymentFailure.InvalidResponse
            next = when {
                response.successful -> record.copy(stage = PaymentStage.SUCCESS, error = null)
                record.stage == PaymentStage.AWAITING_FULFILLMENT -> record.copy(error = null, retryAction = null)
                response.terminalFailure -> record.copy(stage = PaymentStage.FAILED,
                    error = response.safeFailureReason ?: if (response.status == "expired") "timeout" else "payment_failed", retryAction = response.safeRetryAction)
                response.status == "paid" -> record.copy(stage = PaymentStage.AWAITING_FULFILLMENT, error = null, retryAction = null)
                response.status == "pending" -> record.copy(
                    stage = if (record.thirdParty) PaymentStage.CHECKOUT else PaymentStage.OFFICIAL_LAUNCHED,
                    error = response.safeFailureReason,
                    retryAction = response.safeRetryAction,
                )
                else -> record.copy(stage = PaymentStage.UNCERTAIN, error = "verification_failed")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            owner(session)
            next = if (record.stage == PaymentStage.AWAITING_FULFILLMENT) record
            else if ((failure as? PaymentFailure.Http)?.status == 403)
                record.copy(stage = PaymentStage.ACCESS_DENIED, error = "payment_order_forbidden")
            else record.copy(stage = if (record.thirdParty) record.stage else PaymentStage.UNCERTAIN,
                error = safeError(failure, "verification_failed"))
        }
        if (next.stage == PaymentStage.SUCCESS) {
            save(next, session, false, publish)
            event(next, if (closing) "paid_on_close" else "paid_while_open")
            tracker.purchaseSuccessOnIo(paymentValues(next, "success", "fulfilled"), next.userId,
                next.orderId?.let { "payment:$it:fulfilled:purchase_success" })
            return next
        }
        val awaiting = next.stage == PaymentStage.AWAITING_FULFILLMENT
        val timeout = next.thirdParty && next.unresolved && next.expired(clock.now())
        if (closing && timeout) {
            if (!awaiting && record.stage != PaymentStage.TIMED_OUT) {
                event(record, "poll_timeout")
                if (record.thirdParty) event(record, "timeout_cancel")
            }
            if (!awaiting) next = next.copy(stage = PaymentStage.TIMED_OUT, error = "timeout")
        } else if (closing) {
            event(record, "user_cancel")
            if (next.unresolved && !awaiting) next = next.copy(stage = PaymentStage.CLOSED)
        } else if (timeout) {
            if (!awaiting && record.stage != PaymentStage.TIMED_OUT) {
                event(record, "poll_timeout")
                if (record.thirdParty) event(record, "timeout_cancel")
            }
            if (!awaiting) next = next.copy(stage = PaymentStage.TIMED_OUT, error = "timeout")
        }
        val dismissal = dismissals[record.key] ?: record.stage.takeIf { it in setOf(PaymentStage.CLOSED, PaymentStage.TIMED_OUT) }
        if (next.unresolved && !awaiting && dismissal != null) next = next.copy(stage = dismissal,
            error = if (dismissal == PaymentStage.TIMED_OUT) "timeout" else next.error)
        save(next, session, visible && !closing && !timeout && dismissal == null && next.unresolved, publish)
        if (publish && next.stage == PaymentStage.FAILED && record.stage != PaymentStage.FAILED) notifyFailure(session)
        return next
    }
    suspend fun close(key: String) {
        dismissVisible(key, PaymentStage.CLOSED)
        guarded { session ->
            val record = current(key, session) ?: return@guarded
            if (record.thirdParty && record.unresolved && record.orderId != null) {
                val closed = if (record.stage == PaymentStage.AWAITING_FULFILLMENT) record else
                    record.copy(stage = if (record.expired(clock.now())) PaymentStage.TIMED_OUT else PaymentStage.CLOSED)
                save(closed, session, false)
                query(closed, session, closing = true)
            } else mutable.value = mutable.value.copy(checkoutVisible = false)
            presentWaitingSuccess(session)
        }
    }
    private fun dismissVisible(key: String, stage: PaymentStage) {
        val session = sessions.current ?: return
        mutable.update { snapshot ->
            if (snapshot.epoch != session.epoch || sessions.current?.epoch != session.epoch) return@update snapshot
            val record = snapshot.record?.takeIf { it.key == key && it.userId == session.userId && it.thirdParty && it.unresolved }
                ?: return@update snapshot
            val dismissedStage = if (record.expired(clock.now())) PaymentStage.TIMED_OUT else stage
            dismissals[key] = dismissedStage
            snapshot.copy(record = if (record.stage == PaymentStage.AWAITING_FULFILLMENT) record else record.copy(stage = dismissedStage,
                error = if (dismissedStage == PaymentStage.TIMED_OUT) "timeout" else record.error), checkoutVisible = false, busy = false)
        }
    }
    suspend fun expireCheckout(key: String) {
        val record = mutable.value.record?.takeIf { it.key == key && it.thirdParty && it.unresolved && it.expired(clock.now()) } ?: return
        dismissVisible(key, PaymentStage.TIMED_OUT)
        guarded { session ->
            val latest = current(record.key, session) ?: return@guarded
            if (!latest.thirdParty || !latest.unresolved || !latest.expired(clock.now())) return@guarded
            save(if (latest.stage == PaymentStage.AWAITING_FULFILLMENT) latest else
                latest.copy(stage = PaymentStage.TIMED_OUT, error = "timeout"), session, false)
            if (latest.stage !in setOf(PaymentStage.TIMED_OUT, PaymentStage.AWAITING_FULFILLMENT)) { event(latest, "poll_timeout"); event(latest, "timeout_cancel") }
        }
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
            val now = clock.now()
            val launched = record.copy(
                stage = PaymentStage.OFFICIAL_LAUNCHED,
                openedAt = record.openedAt ?: now,
                deadline = record.deadline ?: now + config.maxSeconds * 1000L,
            )
            save(launched, session, false)
            result = launched
        }
        return result
    }
    suspend fun officialResult(key: String, cancelledOrUnavailable: Boolean, consumed: Boolean = false, failed: Boolean = false): Boolean {
        var fulfilled = false
        guarded { session ->
            val record = current(key, session) ?: return@guarded
            if (record.stage == PaymentStage.SUCCESS) {
                fulfilled = true
                return@guarded
            }
            if (record.stage == PaymentStage.AWAITING_FULFILLMENT) {
                fulfilled = query(record, session, closing = false).stage == PaymentStage.SUCCESS
                return@guarded
            }
            // 不把 consume 完成当作已发币，继续等真实充值通知。
            val now = clock.now()
            save(record.copy(
                stage = when {
                    cancelledOrUnavailable -> PaymentStage.CLOSED
                    failed -> PaymentStage.FAILED
                    consumed -> PaymentStage.OFFICIAL_CONSUMED
                    else -> PaymentStage.OFFICIAL_LAUNCHED
                },
                openedAt = if (!cancelledOrUnavailable && !consumed) record.openedAt ?: now else record.openedAt,
                deadline = if (!cancelledOrUnavailable && !consumed) record.deadline ?: now + config.maxSeconds * 1000L else record.deadline,
            ), session, false)
            if (!cancelledOrUnavailable && !consumed && !failed) {
                current(key, session)?.let { fulfilled = query(it, session, closing = false).stage == PaymentStage.SUCCESS }
            }
            if (failed) notifyFailure(session)
            if (cancelledOrUnavailable || consumed) presentWaitingSuccess(session)
        }
        return fulfilled
    }
    suspend fun officialQuote(key: String, price: Double?, currency: String?) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        val validPrice = price?.takeIf { it.isFinite() && it >= 0 } ?: return@guarded
        val validCurrency = currency?.trim()?.uppercase(java.util.Locale.ROOT)
            ?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return@guarded
        save(record.copy(quotePrice = validPrice, quoteCurrency = validCurrency, quoteSource = "google_play"), session, false)
    }
    suspend fun recharge(orderId: String?): Boolean {
        var handled = true
        guarded { session ->
            val matching = records.lastOrNull { it.userId == session.userId && it.orderId == orderId && orderId != null }
            if (matching != null) {
                handled = true
                if (matching.stage == PaymentStage.SUCCESS) return@guarded
                val present = mutable.value.record == null || mutable.value.record?.key == matching.key
                if (matching.initialized != null || matching.stage == PaymentStage.AWAITING_FULFILLMENT) query(matching, session, false, present)
            } else {
                val pending = records.filter { it.userId == session.userId && it.initialized != null }
                handled = records.any { it.userId == session.userId }
                pending.filter { it.unresolved && it.orderId != null }.forEach { query(it, session, false, mutable.value.record == null || mutable.value.record?.key == it.key) }
            }
        }
        return handled
    }
    private fun paymentValues(record: PaymentRecord, status: String, stage: String, reason: String? = null): Map<String, Any> =
        paymentProperties(status, record.initialized?.channelCode ?: "unknown", "SERVICE", record.source, record.productId,
            record.orderId, stage, reason) + paymentPriceProperties(record.quotePrice, record.quoteCurrency, record.quoteSource) +
            mapOf("af_content_id" to (record.initialized?.sdkParams?.productId?.takeIf { it.isNotBlank() } ?: record.productId))
    suspend fun shown(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.stage == PaymentStage.SUCCESS && record.successShownAt == null) {
            save(record.copy(successShownAt = clock.now()), session, publish = false)
        }
    }
    suspend fun acknowledge(key: String) = guarded { session ->
        val record = current(key, session) ?: return@guarded
        if (record.stage == PaymentStage.SUCCESS) {
            val wasCurrent = mutable.value.record?.key == key
            save(record.copy(successAcknowledged = true), session, publish = false)
            if (!wasCurrent) return@guarded
            val next = records.firstOrNull { it.userId == session.userId && it.stage == PaymentStage.SUCCESS && !it.successAcknowledged }
            next?.let {
                mutable.value = PaymentViewState(session.epoch, it.copy(source = "restored"))
            } ?: run { mutable.value = PaymentViewState(session.epoch) }
        }
    }
}
