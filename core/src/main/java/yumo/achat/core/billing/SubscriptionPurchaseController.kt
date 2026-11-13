package yumo.achat.core.billing

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import yumo.achat.core.analytics.*
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ServiceFailure
import yumo.achat.core.payment.PaymentRepository
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

enum class SubscriptionPurchaseStage { Idle, Loading, Ready, Purchasing, Closing, Pending, AwaitingFulfillment, Success, Cancelled, Error }
data class SubscriptionPurchaseUiState(
    val stage: SubscriptionPurchaseStage = SubscriptionPurchaseStage.Idle,
    val products: List<BillingProduct> = emptyList(),
    val productId: String? = null,
    val error: String? = null,
    val canRetrySamePlan: Boolean = false,
    val canChangePlan: Boolean = false,
    val fulfilledOrderId: String? = null, val ownerUserId: String? = null,
    val hasUnresolvedOrder: Boolean = false,
    val unavailableProductIds: Set<String> = emptySet(),
)

enum class SubscriptionCheckoutAction { Buy, Resume, Check, Unavailable }

fun subscriptionCheckoutAction(state: SubscriptionPurchaseUiState, selectedProduct: String?): SubscriptionCheckoutAction = when {
    state.hasUnresolvedOrder && state.stage == SubscriptionPurchaseStage.Error && !state.canRetrySamePlan -> SubscriptionCheckoutAction.Check
    state.hasUnresolvedOrder && state.canRetrySamePlan && !state.canChangePlan -> SubscriptionCheckoutAction.Resume
    selectedProduct in state.unavailableProductIds -> SubscriptionCheckoutAction.Unavailable
    else -> SubscriptionCheckoutAction.Buy
}

/** Evaluate only the current successful Play query; no ownership result is retained across requests. */
internal fun activeSubscriptionOwnership(products: Map<String, String>, purchases: List<BillingPurchase>): Map<String, String> {
    check(purchases.none { it.productType == BillingProductType.SUBS && it.productId in products.values &&
        it.purchaseState == PurchaseState.UNSPECIFIED }) { "Subscription purchase state is unknown" }
    return products.filterValues { sku -> purchases.any { it.productType == BillingProductType.SUBS && it.productId == sku &&
        it.purchaseState in setOf(PurchaseState.PENDING, PurchaseState.PURCHASED) } }
}

/** Only a full-price, indefinitely recurring base plan matching the actual requested period is admissible. */
fun selectSubscriptionOffer(product: BillingProduct, period: String): SubscriptionOfferDetails? =
    product.subscriptionOfferDetails.orEmpty().filter {
        it.offerId.isBlank() && it.offerToken.isNotBlank() && it.pricingPhases.size == 1 &&
            it.pricingPhases.single().let { phase ->
                phase.billingPeriod == period && phase.recurrenceMode == 1 && phase.priceAmountMicros >= 0 && phase.priceFormatted.isNotBlank()
            }
    }.singleOrNull()

internal enum class SubscriptionRetryDecision { Blocked, SamePlanOnly, Replaceable }

/** Empty Play results alone never establish that the user cancelled the dialog. */
internal fun subscriptionRetryDecision(
    userCancelled: Boolean?, status: String, hasStorePurchase: Boolean, querySucceeded: Boolean = true,
): SubscriptionRetryDecision = when {
    !querySucceeded || hasStorePurchase || status == "paid" -> SubscriptionRetryDecision.Blocked
    status in setOf("failed", "cancelled", "expired") -> SubscriptionRetryDecision.Replaceable
    status == "pending" && userCancelled == true -> SubscriptionRetryDecision.Replaceable
    status == "pending" -> SubscriptionRetryDecision.SamePlanOnly
    else -> SubscriptionRetryDecision.Blocked
}

internal fun canArchiveRejectedSubscriptionCreation(
    operation: String, httpStatus: Int?, reason: String?, hasOrderId: Boolean, sameRequest: Boolean,
): Boolean = operation == "create_order" && httpStatus == 409 && reason == "PAYMENT_PRODUCT_UNAVAILABLE" &&
    !hasOrderId && sameRequest

internal enum class SubscriptionOrderPricing { Legacy, Standard }
internal fun subscriptionOrderPricing(mode: String?): SubscriptionOrderPricing? = when (mode) {
    null -> SubscriptionOrderPricing.Legacy
    "standard" -> SubscriptionOrderPricing.Standard
    else -> null
}

@Serializable
private data class SubscriptionOrder(
    val owner: String, val productId: String, val sku: String, val period: String,
    val requestId: String, val orderId: String? = null,
    val priceMicros: Long? = null, val currency: String? = null,
    val userCancelled: Boolean? = null,
    val pricingMode: String? = null,
    val trigger: String? = null, val isVip: Boolean? = null,
    val purchaseId: String? = null, val fulfilled: Boolean = false,
    val orderCreationSource: String? = null, val orderCreateStarted: Boolean = false,
    val obfuscatedAccountId: String? = null, val obfuscatedProfileId: String? = null,
) { override fun toString() = "SubscriptionOrder(identity hidden)" }

@Singleton
class SubscriptionPurchaseController @Inject constructor(
    private val manager: BillingManager,
    private val admission: BillingPurchaseAdmission,
    private val payments: PaymentRepository,
    private val orders: yumo.achat.core.payment.PaymentOrderFactory,
    private val sessions: SessionCoordinator,
    private val json: Json,
    private val tracker: EventTracker,
    @ApplicationContext context: Context,
    config: BillingConfiguration,
) {
    private val prefs = context.getSharedPreferences(config.storageName + "_subscriptions", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SubscriptionPurchaseUiState())
    val state = mutable.asStateFlow()
    private var lease: BillingPurchaseLease? = null
    private var epoch: String? = sessions.current?.epoch
    private val recovery = SubscriptionRecoveryWorker(scope) { restore() }
    private val ledger = SubscriptionPurchaseLedger(json, { prefs.getString(it, null) }, { key, value ->
        check(prefs.edit().putString(key, value).commit())
    })
    @Volatile private var activePlayRequest: PurchaseRequest? = null
    fun onForeground() { recovery.request() }

    /** Do not take lock: buy owns it while awaiting Play. Keep the captured request identity. */
    fun onCheckoutReturned() {
        val request = activePlayRequest ?: return
        scope.launch {
            delay(1500) // Give the normal Play callback first opportunity to complete.
            manager.interruptSubscriptionCheckout(request)
        }
    }

    init {
        manager.initialize(context)
        scope.launch {
            var initialEmission = true
            sessions.state.collect {
                var shouldRestore = false
                lock.withLock {
                    // Read the live session after acquiring the lock: a queued emission may already be stale.
                    val session = sessions.current
                    if (epoch != session?.epoch) {
                        lease?.close(); lease = null; epoch = session?.epoch
                        mutable.value = SubscriptionPurchaseUiState()
                        shouldRestore = session != null
                    } else if (initialEmission && session != null) {
                        // Initial restoration must not release a same-session purchase's active lease.
                        shouldRestore = true
                    }
                    initialEmission = false
                }
                // restore takes the same mutex and must run outside the reset critical section.
                if (shouldRestore) onForeground()
            }
        }
        scope.launch {
            manager.recoveredPurchases.collect { purchase ->
                if (purchase.productType == BillingProductType.SUBS) onForeground()
            }
        }
    }

    private fun active(): Session = sessions.current ?: throw ServiceFailure.SignedOut
    private fun requireOwner(session: Session) {
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
    }
    private fun read(owner: String): SubscriptionOrder? = prefs.getString(owner, null)?.let {
        json.decodeFromString<SubscriptionOrder>(it).also { order -> check(order.owner == owner) }
    }
    private suspend fun save(order: SubscriptionOrder) = withContext(Dispatchers.IO) {
        check(prefs.edit().putString(order.owner, json.encodeToString(order)).commit())
    }
    private fun publish(session: Session, stage: SubscriptionPurchaseStage, product: String? = null, error: String? = null,
        canRetrySamePlan: Boolean = false, canChangePlan: Boolean = false, fulfilledOrderId: String? = null) {
        requireOwner(session)
        mutable.value = mutable.value.copy(stage = stage, productId = product, error = error,
            canRetrySamePlan = canRetrySamePlan, canChangePlan = canChangePlan,
            hasUnresolvedOrder = prefs.contains(session.userId),
            ownerUserId = session.userId, fulfilledOrderId = fulfilledOrderId ?: mutable.value.fulfilledOrderId)
    }
    private fun history(owner: String): List<SubscriptionOrder> = prefs.getString(owner + "_history", null)?.let {
        json.decodeFromString<List<SubscriptionOrder>>(it).also { orders -> check(orders.all { order -> order.owner == owner }) }
    }.orEmpty()
    private suspend fun archive(session: Session, order: SubscriptionOrder) = withContext(Dispatchers.IO) {
        requireOwner(session)
        val records = (history(session.userId) + order).distinctBy { it.requestId }
        check(prefs.edit().putString(session.userId + "_history", json.encodeToString(records))
            .remove(session.userId).commit())
    }
    private fun hasStorePurchase(purchases: List<BillingPurchase>, sku: String): Boolean = purchases.any {
        it.productId == sku && it.purchaseState in setOf(PurchaseState.PENDING, PurchaseState.PURCHASED)
    }

    suspend fun queryProducts(ids: List<String>): List<BillingProduct> = lock.withLock {
        val session = active()
        val unresolved = prefs.contains(session.userId)
        if (!unresolved) publish(session, SubscriptionPurchaseStage.Loading)
        try {
            check(manager.connect().isSuccess)
            val result = manager.queryProducts(ids.distinct(), BillingProductType.SUBS)
            check(result.isSuccess)
            val products = result.data.orEmpty()
            requireOwner(session)
            mutable.value = mutable.value.copy(
                stage = if (unresolved) mutable.value.stage else SubscriptionPurchaseStage.Ready,
                products = products,
            )
            products
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            publish(session, SubscriptionPurchaseStage.Error, error = "subscription_products_unavailable")
            throw failure
        }
    }

    suspend fun buy(activity: Activity, productId: String, sku: String, billingPeriod: String, trigger: String = "subscription", isVip: Boolean? = null) = lock.withLock {
        val session = active()
        var operation = "admission"
        var creatingRequestId: String? = null
        try {
            if (productId in mutable.value.unavailableProductIds) {
                publish(session, SubscriptionPurchaseStage.Error, productId, "subscription_product_unavailable", canChangePlan = true)
                return@withLock
            }
            if (lease == null) lease = admission.tryAcquire() ?: error("billing_busy")
            publish(session, SubscriptionPurchaseStage.Purchasing, productId)
            var existing = read(session.userId)
            if (existing?.userCancelled == true && !existing.fulfilled) {
                clearCancelledOrder(existing)
                existing = null
            }
            operation = "billing_connect"
            check(manager.connect().isSuccess)
            operation = "query_product"
            val queried = manager.queryProducts(listOf(sku), BillingProductType.SUBS)
            val product = queried.data?.singleOrNull { it.id == sku } ?: error("subscription_product_unavailable")
            val offer = selectSubscriptionOffer(product, billingPeriod) ?: error("subscription_plan_unavailable")
            val phase = offer.pricingPhases.single()
            requireOwner(session)
            operation = "query_existing_purchases"
            val beforeOrder = manager.queryPurchases(BillingProductType.SUBS, emitRecoveryEvents = false)
            check(beforeOrder.isSuccess)
            requireOwner(session)
            val currentPlayStates = beforeOrder.data.orEmpty().filter { it.productId == sku }
                .map { it.purchaseState.name }.distinct().joinToString(",").ifBlank { "NONE" }
            android.util.Log.i("SubscriptionCheckout", "fresh_ownership_query response=${beforeOrder.responseCode} states=$currentPlayStates")
            val activeStoreOwnership = activeSubscriptionOwnership(mapOf(productId to sku), beforeOrder.data.orEmpty())
            if (existing != null) {
                val previous = existing
                val status = previous.orderId?.let { payments.status(it, session) }
                val hasPurchase = hasStorePurchase(beforeOrder.data.orEmpty(), previous.sku)
                val decision = subscriptionRetryDecision(previous.userCancelled, status?.status.orEmpty(), hasPurchase)
                requireOwner(session)
                if (previous.orderId != null && (status?.status == "paid" || hasPurchase)) {
                    reconcile(session, previous)
                    return@withLock
                }
                if (decision == SubscriptionRetryDecision.Blocked && previous.orderId != null) {
                    publish(session, SubscriptionPurchaseStage.Error, previous.productId, "subscription_verification_pending")
                    return@withLock
                }
                if (decision == SubscriptionRetryDecision.Replaceable &&
                    (previous.productId != productId || status?.terminalFailure == true)) {
                    archive(session, previous)
                    existing = null
                } else if (previous.productId != productId) {
                    publish(session, SubscriptionPurchaseStage.Error, previous.productId,
                        if (decision == SubscriptionRetryDecision.SamePlanOnly) "subscription_retry_same_plan" else "subscription_verification_pending",
                        canRetrySamePlan = decision == SubscriptionRetryDecision.SamePlanOnly)
                    if (decision == SubscriptionRetryDecision.SamePlanOnly) { lease?.close(); lease = null }
                    return@withLock
                }
            }
            if (existing == null && activeStoreOwnership.isNotEmpty()) {
                publish(session, SubscriptionPurchaseStage.Error, productId, "subscription_already_owned", canChangePlan = true)
                return@withLock
            }
            var order = existing ?: SubscriptionOrder(session.userId, productId, sku, billingPeriod, UUID.randomUUID().toString(),
                trigger = trigger, isVip = isVip, purchaseId = UUID.randomUUID().toString(),
                orderCreationSource = orders.creationSource).also { save(it) }
            if (order.orderId == null) {
                operation = "create_order"
                creatingRequestId = order.requestId
                if (!yumo.achat.core.payment.canReplayOrderCreation(order.orderCreationSource, order.orderCreateStarted)) {
                    publish(session, SubscriptionPurchaseStage.Error, order.productId, "subscription_order_creation_unknown")
                    return@withLock
                }
                val source = yumo.achat.core.payment.orderCreationSource(order.orderCreationSource) ?: error("invalid_order_source")
                if (source == yumo.achat.core.payment.OrderCreationSource.Business) {
                    order = order.copy(orderCreateStarted = true)
                    save(order)
                }
                val created = if (source == yumo.achat.core.payment.OrderCreationSource.Business) {
                    orders.createBusiness(productId, order.requestId, order.trigger ?: "subscription", session)
                } else payments.createOrderWithTrigger(productId, order.requestId, order.trigger, session)
                requireOwner(session)
                order = order.copy(orderId = created.orderId, obfuscatedAccountId = created.obfuscatedAccountId,
                    obfuscatedProfileId = created.obfuscatedProfileId); save(order)
            }
            val owned = manager.queryPurchases(BillingProductType.SUBS, emitRecoveryEvents = false)
            check(owned.isSuccess)
            if (owned.data.orEmpty().any {
                val tracked = manager.trackedPurchaseRecord(it.purchaseToken)
                tracked?.ownerUserId == session.userId && tracked.businessOrderId == order.orderId
            } || payments.status(order.orderId!!, session).status == "paid") {
                reconcile(session, order); return@withLock
            }
            operation = "initialize_payment"
            val route = payments.initialize(order.orderId!!, session)
            android.util.Log.i("SubscriptionCheckout", "initialized type=${route.channelType} channel=${route.channelCode} skuMatch=${route.sdkParams?.productId == sku}")
            check(route.channelType == "official" && route.channelCode == "google_play" && route.sdkParams?.productId == sku)
            requireOwner(session)
            order = order.copy(priceMicros = order.priceMicros ?: phase.priceAmountMicros, currency = order.currency ?: product.currencyCode, userCancelled = false)
            save(order)
            prepareSnapshot(order)
            publish(session, SubscriptionPurchaseStage.Purchasing, productId)
            val initiated = paymentProperties("initiated", "google_play", "subscription", order.trigger ?: "subscription", productId,
                order.orderId, "sdk_launch", amount = phase.priceAmountMicros.toDouble() / 1_000_000,
                currency = product.currencyCode) + mapOf("af_content_id" to sku)
            withContext(Dispatchers.IO) {
                try { tracker.track("initiate_pay", initiated + paymentRechargeProperties(initiated, session.userId), session.userId) }
                catch (_: Exception) { /* Analytics cannot prevent a purchase. */ }
            }
            requireOwner(session)
            operation = "launch_play"
            val request = withContext(Dispatchers.Main.immediate) {
                manager.launchPurchaseFlow(activity, sku, BillingProductType.SUBS, offer.offerToken,
                    userId = order.obfuscatedAccountId ?: session.userId,
                    orderId = order.obfuscatedProfileId ?: order.orderId, ownerUserId = session.userId,
                    businessOrderId = order.orderId, priceAmountMicros = phase.priceAmountMicros,
                    priceCurrencyCode = product.currencyCode)
            }
            operation = "await_play_result"
            activePlayRequest = request
            val result = try {
                withContext(NonCancellable) { manager.waitForPurchaseResult(request) }
            } finally { activePlayRequest = null }
            if (result.isInterruptedSubscriptionCheckout()) {
                android.util.Log.i("SubscriptionCheckout", "checkout_result_unknown reconciling=true")
                // A timeout/return is never proof of cancellation. Keep the exact order and token mapping.
                scope.launch {
                    val late = request.await()
                    if (late.responseCode == BillingResponseCode.CANCELLED) lock.withLock {
                        if (read(order.owner)?.requestId == order.requestId) {
                            clearCancelledOrder(order)
                            if (sessions.current?.epoch == session.epoch) {
                                lease?.close(); lease = null
                                publish(session, SubscriptionPurchaseStage.Cancelled, order.productId,
                                    canRetrySamePlan = true, canChangePlan = true)
                            }
                        }
                    }
                    onForeground()
                }
                val reconciled = withTimeoutOrNull(45_000) { reconcile(session, order); true } ?: false
                if (!reconciled) publish(session, SubscriptionPurchaseStage.Error, productId,
                    "subscription_verification_pending")
                return@withLock
            }
            // Finish Play obligations using the frozen owner even if the current account changed.
            if (result.isSuccess) result.data?.let { processPurchased(order, it) }
            if (result.responseCode == BillingResponseCode.CANCELLED) {
                finishCancelledSubscription(
                    clearPending = { clearCancelledOrder(order) },
                    release = { lease?.close(); lease = null },
                    publish = {
                        if (sessions.current?.epoch == session.epoch) publish(session,
                            SubscriptionPurchaseStage.Cancelled, productId, canRetrySamePlan = true, canChangePlan = true)
                    },
                )
                return@withLock
            }
            requireOwner(session)
            when {
                result.data?.purchaseState == PurchaseState.PENDING -> publish(session, SubscriptionPurchaseStage.Pending, productId)
                result.isSuccess && result.data?.purchaseState == PurchaseState.PURCHASED -> reconcile(session, order)
                else -> publish(session, SubscriptionPurchaseStage.Error, productId, "subscription_payment_unavailable")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.w("SubscriptionCheckout", "failed stage=$operation type=${failure.javaClass.simpleName}" +
                ((failure as? yumo.achat.core.payment.PaymentFailure.Http)?.let { " http=${it.status} reason=${it.reason}" } ?: ""))
            if (sessions.current?.epoch == session.epoch) {
                val saved = runCatching { read(session.userId) }.getOrNull()
                val rejected = failure as? yumo.achat.core.payment.PaymentFailure.Http
                val canArchive = saved != null && canArchiveRejectedSubscriptionCreation(
                    operation, rejected?.status, rejected?.reason, saved.orderId != null,
                    creatingRequestId != null && saved.requestId == creatingRequestId && saved.productId == productId,
                )
                if (canArchive && saved != null) {
                    // A definitive rejected create has no chargeable business order; retain its request in history.
                    try {
                        archive(session, saved)
                        lease?.close(); lease = null
                        mutable.value = mutable.value.copy(unavailableProductIds = mutable.value.unavailableProductIds + productId)
                        publish(session, SubscriptionPurchaseStage.Error, productId, "subscription_product_unavailable", canChangePlan = true)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        publish(session, SubscriptionPurchaseStage.Error, productId, "subscription_verification_pending")
                    }
                } else publish(session, SubscriptionPurchaseStage.Error, saved?.productId ?: productId,
                    "subscription_payment_unavailable", canRetrySamePlan = saved != null && saved.orderId == null &&
                        yumo.achat.core.payment.canReplayOrderCreation(saved.orderCreationSource, saved.orderCreateStarted))
            }
        } finally {
            if (!prefs.contains(session.userId)) { lease?.close(); lease = null }
            if (sessions.current?.epoch == session.epoch && prefs.contains(session.userId)) onForeground()
        }
    }

    suspend fun restore(): Boolean = lock.withLock {
        val session = sessions.current ?: return@withLock true
        var historyFailed = false
        try {
            val order = read(session.userId)
            if (order?.userCancelled == true && !order.fulfilled) {
                finishCancelledSubscription(
                    clearPending = { clearCancelledOrder(order) },
                    release = { lease?.close(); lease = null },
                    publish = { if (sessions.current?.epoch == session.epoch) publish(session,
                        SubscriptionPurchaseStage.Cancelled, order.productId, canRetrySamePlan = true, canChangePlan = true) },
                )
                return@withLock true
            }
            val recoverableHistory = history(session.userId).filterNot { it.userCancelled == true }
            if (order == null && recoverableHistory.none { it.orderId != null }) return@withLock true
            check(manager.connect().isSuccess)
            // A bounded pass keeps canceled attempts visible to late backend results without blocking safe new selection.
            for (old in recoverableHistory.takeLast(10)) {
                try {
                    reconcileHistorical(session, old)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // Keep this history item for later. It cannot replace the current attempt's UI or admission.
                    requireOwner(session)
                    historyFailed = true
                }
            }
            if (order == null) return@withLock !historyFailed
            if (lease == null) lease = admission.tryAcquire() ?: return@withLock false
            check(manager.connect().isSuccess)
            if (order.orderId == null) {
                val canReplay = yumo.achat.core.payment.canReplayOrderCreation(order.orderCreationSource, order.orderCreateStarted)
                publish(session, SubscriptionPurchaseStage.Error, order.productId,
                    if (canReplay) "subscription_retry_required" else "subscription_order_creation_unknown", canRetrySamePlan = canReplay)
                return@withLock true
            }
            reconcile(session, order)
            !historyFailed && ((mutable.value.stage in setOf(SubscriptionPurchaseStage.Cancelled, SubscriptionPurchaseStage.Ready) || mutable.value.canRetrySamePlan) ||
                !prefs.contains(session.userId))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (sessions.current?.epoch == session.epoch) {
                val saved = runCatching { read(session.userId) }.getOrNull()
                if (saved?.fulfilled == true) publish(session, SubscriptionPurchaseStage.Success, saved.productId, fulfilledOrderId = saved.orderId)
                else if (saved != null) publish(session, SubscriptionPurchaseStage.Error, saved.productId, "subscription_verification_pending")
            }
            false
        }
    }

    private suspend fun clearCancelledOrder(order: SubscriptionOrder) = withContext(Dispatchers.IO) {
        // Only this frozen owner/request is removed, even when cancellation arrives after account switch.
        val cancelled = order.copy(userCancelled = true)
        val records = history(order.owner).filterNot { it.requestId == order.requestId } + cancelled
        val editor = prefs.edit().putString(order.owner + "_history", json.encodeToString(records))
        if (read(order.owner)?.requestId == order.requestId) editor.remove(order.owner)
        check(editor.commit())
    }

    private fun prepareSnapshot(order: SubscriptionOrder) {
        val orderId = order.orderId ?: return
        ledger.prepare(SubscriptionPurchaseSnapshot(order.purchaseId ?: order.requestId, order.owner, orderId,
            order.sku, order.trigger, order.isVip, order.priceMicros, order.currency))
    }

    private suspend fun processPurchased(order: SubscriptionOrder, purchase: BillingPurchase): Boolean =
        handlePurchasedSubscription(purchase.purchaseState, report = {
            withContext(Dispatchers.IO) {
                prepareSnapshot(order) // Legacy records retain only facts that were actually saved.
                val snapshot = ledger.claim(order.owner, order.orderId!!, purchase.purchaseToken, purchase.orderId)
                if (snapshot != null) {
                    val values = subscriptionPurchaseProperties(snapshot, order.productId)
                    tracker.purchaseSuccessOnIo(values, snapshot.owner, "play:${snapshot.orderId}:purchase_success")
                }
            }
        }, acknowledge = {
            purchase.isAcknowledged || manager.acknowledgePurchase(purchase.purchaseToken).isSuccess
        })

    private suspend fun reconcileHistorical(session: Session, order: SubscriptionOrder) {
        requireOwner(session)
        val orderId = order.orderId ?: return
        val queried = manager.queryPurchases(BillingProductType.SUBS, emitRecoveryEvents = false)
        check(queried.isSuccess)
        val purchase = queried.data.orEmpty().singleOrNull {
            val tracked = manager.trackedPurchaseRecord(it.purchaseToken)
            it.productType == BillingProductType.SUBS && it.productId == order.sku &&
                it.purchaseState == PurchaseState.PURCHASED && tracked?.ownerUserId == session.userId &&
                tracked.businessOrderId == orderId
        } ?: return // Backend success alone cannot attribute an untracked Play purchase.
        val acknowledged = processPurchased(order, purchase)
        requireOwner(session)
        if (!payments.status(orderId, session).successful) error("subscription_recovery_pending")
        requireOwner(session)
        mutable.value = mutable.value.copy(fulfilledOrderId = orderId, ownerUserId = session.userId)
        if (!acknowledged) error("subscription_ack_pending")
        withContext(Dispatchers.IO) {
            requireOwner(session)
            check(prefs.edit().putString(session.userId + "_history",
                json.encodeToString(history(session.userId).filterNot { it.requestId == order.requestId })).commit())
        }
        runCatching { manager.markRecoveredPurchaseHandled(purchase.purchaseToken) }
        // Historical completion refreshes membership without changing the current purchase or its lease.
    }

    private suspend fun reconcile(session: Session, order: SubscriptionOrder) {
        publish(session, if (order.fulfilled) SubscriptionPurchaseStage.Success else SubscriptionPurchaseStage.AwaitingFulfillment,
            order.productId, fulfilledOrderId = order.orderId.takeIf { order.fulfilled })
        val result = manager.queryPurchases(BillingProductType.SUBS, emitRecoveryEvents = false)
        check(result.isSuccess)
        val purchase = result.data.orEmpty().singleOrNull {
            val tracked = manager.trackedPurchaseRecord(it.purchaseToken)
            it.productId == order.sku && tracked?.ownerUserId == session.userId && tracked.businessOrderId == order.orderId
        }
        val acknowledged = purchase?.let { processPurchased(order, it) } ?: false
        repeat(20) {
            requireOwner(session)
            val status = if (order.fulfilled) yumo.achat.core.payment.PaymentOrderStatus(order.orderId!!, "paid", "fulfilled")
                else payments.status(order.orderId!!, session)
            if (status.successful) {
                check(purchase != null && purchase.purchaseState == PurchaseState.PURCHASED)
                requireOwner(session)
                if (!acknowledged && !order.fulfilled) save(order.copy(fulfilled = true))
                withContext(Dispatchers.IO) {
                    requireOwner(session)
                    val editor = prefs.edit()
                    if (acknowledged && read(session.userId)?.requestId == order.requestId) editor.remove(session.userId)
                    editor.putString(session.userId + "_history", json.encodeToString(history(session.userId).filterNot { it.requestId == order.requestId }))
                    check(editor.commit())
                }
                // Fulfillment and acknowledgement already succeeded; cleanup cannot undo either result.
                if (acknowledged) runCatching { manager.markRecoveredPurchaseHandled(purchase.purchaseToken) }
                publish(session, SubscriptionPurchaseStage.Success, order.productId, fulfilledOrderId = order.orderId)
                lease?.close(); lease = null
                return
            }
            val decision = subscriptionRetryDecision(order.userCancelled, status.status,
                hasStorePurchase(result.data.orEmpty(), order.sku))
            if (decision == SubscriptionRetryDecision.Replaceable) {
                if (status.terminalFailure && read(session.userId)?.requestId == order.requestId) archive(session, order)
                publish(session, SubscriptionPurchaseStage.Cancelled, order.productId,
                    canRetrySamePlan = true, canChangePlan = true)
                lease?.close(); lease = null
                return
            }
            if (decision == SubscriptionRetryDecision.SamePlanOnly) {
                val checkoutActive = manager.hasActiveSubscriptionCheckout()
                publish(session, SubscriptionPurchaseStage.Error, order.productId,
                    if (checkoutActive) "subscription_verification_pending" else "subscription_retry_same_plan",
                    canRetrySamePlan = !checkoutActive)
                lease?.close(); lease = null
                return
            }
            if (status.terminalFailure) {
                publish(session, SubscriptionPurchaseStage.Error, order.productId, "subscription_order_failed")
                return
            }
            if (purchase?.purchaseState == PurchaseState.PENDING) {
                publish(session, SubscriptionPurchaseStage.Pending, order.productId); return
            }
            delay(1500)
        }
        publish(session, SubscriptionPurchaseStage.Error, order.productId, "subscription_verification_pending")
    }
}
