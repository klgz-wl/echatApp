package yumo.achat.core.billing

import android.app.Activity
import yumo.achat.core.analytics.*
import android.content.Context
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.wallet.WalletRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 迁移参考 BillingRepositoryImpl 的消耗型商品链路，保留恢复与 token 幂等处理。 */
@Singleton
class BillingRepository @Inject constructor(private val manager: BillingManager,
    private val wallet: WalletRepository, private val sessions: SessionCoordinator,
    @ApplicationContext context: Context, private val events: EventTracker,
    private val config: BillingConfiguration) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Mutex()
    private val fulfillment = PurchaseFulfillmentCoordinator(scope, manager::markRecoveredPurchaseHandled)
    private val mutableRecovered = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val recovered = mutableRecovered.asSharedFlow()
    init {
        manager.initialize(context)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.recoveredPurchases.collect(::fulfillRecovered) }
    }
    suspend fun initialize(): BillingResult<Unit> = initialization.withLock {
        val result = manager.connect()
        if (result.isSuccess) reconcile()
        result
    }
    fun onForeground() { scope.launch { initialize() } }
    suspend fun queryProducts(ids: List<String>): BillingResult<List<BillingProduct>> =
        if (ids.isEmpty()) BillingResult.success(emptyList()) else manager.queryProducts(ids, BillingProductType.IN_APP)
    suspend fun purchase(activity: Activity, productId: String, ownerUserId: String, businessOrderId: String,
        obfuscatedAccountId: String, obfuscatedProfileId: String): BillingResult<BillingPurchase> {
        val request = withContext(Dispatchers.Main.immediate) {
            manager.launchPurchaseFlow(activity, productId, BillingProductType.IN_APP,
                userId = obfuscatedAccountId, orderId = obfuscatedProfileId,
                ownerUserId = ownerUserId, businessOrderId = businessOrderId)
        }
        // 与参考实现一致：取消页面观察不会把 Play 全局回调交给下一笔订单。
        return withContext(NonCancellable) { manager.waitForPurchaseResult(request) }
    }
    suspend fun consume(token: String): BillingResult<Unit> = fulfillment.fulfill(token) { manager.consumePurchase(token) }
    private suspend fun reconcile(): BillingResult<Unit> {
        val result = manager.queryPurchases(BillingProductType.IN_APP)
        if (result.isError) return BillingResult.error(result.responseCode)
        result.data.orEmpty().filter { it.purchaseState == PurchaseState.PURCHASED && manager.isTrackedPurchase(it.purchaseToken) }
            .forEach { fulfillRecovered(it) }
        return BillingResult.success(Unit)
    }
    private suspend fun fulfillRecovered(purchase: BillingPurchase) {
        val tracked = manager.trackedPurchaseRecord(purchase.purchaseToken) ?: return
        val owner = tracked.ownerUserId ?: tracked.userId
        if (config.backendOwnedFulfillment) {
            if (owner != null && sessions.current?.userId == owner) {
                (tracked.businessOrderId ?: tracked.orderId)?.let { mutableRecovered.emit(it) }
            }
            return
        }
        val result = consume(purchase.purchaseToken)
        if (!result.isSuccess || owner == null || sessions.current?.userId != owner) return
        (tracked.businessOrderId ?: tracked.orderId)?.let { orderId ->
            events.paymentResult(mapOf("status" to "success", "af_success" to "true", "pay_method" to "google_play",
                "payment_flow" to "restored", "entry_source" to "restored", "af_content_id" to tracked.productId,
                "af_order_id" to orderId, "stage" to "play_consumed"), owner, "play:$orderId:consumed")
        }
        try {
            wallet.refreshBalance()
            wallet.loadRecords(refresh = true)
            (tracked.businessOrderId ?: tracked.orderId)?.let { mutableRecovered.emit(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 消费结果不因余额刷新失败而重复执行；下次前台继续读取真实余额。 */ }
    }
}
