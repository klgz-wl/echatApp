package yumo.achat.core.billing

import android.app.Activity
import yumo.achat.core.analytics.*
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.wallet.CoinProduct
import yumo.achat.core.wallet.WalletRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class CoinPurchaseStatus { IDLE, BUSY, PENDING, COMPLETED, CANCELLED, FAILED }
data class CoinPurchaseState(val epoch: String? = null, val status: CoinPurchaseStatus = CoinPurchaseStatus.IDLE,
    val failureStage: ConsumablePurchaseStage? = null, val revision: Long = 0)

/** 参考 DiamondViewModel 的顺序移到进程级控制器，离开页面不丢失已开始的交易。 */
@Singleton
class CoinPurchaseController @Inject constructor(private val billing: BillingRepository,
    private val wallet: WalletRepository, private val sessions: SessionCoordinator,
    private val admission: BillingPurchaseAdmission, private val orchestrator: ConsumablePurchaseOrchestrator,
    private val config: BillingConfiguration, private val tracker: EventTracker = NoOpEventTracker) {
    private val mutable = MutableStateFlow(CoinPurchaseState())
    val state = mutable.asStateFlow()
    suspend fun buy(activity: Activity, orderId: String, sku: String, expectedEpoch: String, source: String = config.defaultTrigger, packageId: String = sku) =
        execute(activity, sku, expectedEpoch, source, packageId, "SERVICE") { PurchaseStepResult.Success(orderId) }

    /** 旧流程在连接、查询 Play 成功后才建单；新流程只能传入已初始化的订单。 */
    suspend fun buyLegacy(activity: Activity, product: CoinProduct, expectedEpoch: String, source: String = config.defaultTrigger,
        createOrder: suspend (Session) -> String) = execute(activity, product.playProductId.orEmpty(), expectedEpoch, source, product.id, "LEGACY") { session ->
        try { PurchaseStepResult.Success(createOrder(session)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PurchaseStepResult.Failure() }
    }

    private suspend fun execute(activity: Activity, sku: String, expectedEpoch: String, source: String, packageId: String, flow: String,
        createOrder: suspend (Session) -> PurchaseStepResult<String>) {
        val session = sessions.current ?: return
        if (session.epoch != expectedEpoch) return
        val lease = admission.tryAcquire() ?: return
        val revision = mutable.value.revision + 1
        mutable.value = CoinPurchaseState(session.epoch, CoinPurchaseStatus.BUSY, revision = revision)
        var reportedOrder: String? = null
        var price: Double? = null
        var currency: String? = null
        fun report(status: String, stage: String, reason: String? = null) {
            tracker.paymentResult(paymentProperties(status, "google_play", flow, source, packageId, reportedOrder,
                stage, reason, price, currency) + mapOf("af_content_id" to sku), session.userId,
                reportedOrder?.takeIf { status == "success" }?.let { "play:$it:consumed" })
        }
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            try {
                val result = orchestrator.purchase(
                    createOrder = {
                        if (sessions.current?.epoch != session.epoch) PurchaseStepResult.Failure()
                        else createOrder(session).also { if (it is PurchaseStepResult.Success) reportedOrder = it.value }
                    },
                    initializeBilling = { billing.initialize().toPurchaseStep() },
                    queryStoreProduct = {
                        if (sku.isBlank()) PurchaseStepResult.Failure(code = BillingResponseCode.ITEM_UNAVAILABLE)
                        else {
                            val result = billing.queryProducts(listOf(sku))
                            result.data?.firstOrNull { it.id == sku }?.let { price = it.priceAmountMicros.toDouble() / 1_000_000; currency = it.currencyCode; PurchaseStepResult.Success(it) }
                                ?: PurchaseStepResult.Failure(code = result.responseCode)
                        }
                    },
                    launchPurchase = { orderId ->
                        if (sessions.current?.epoch != session.epoch || activity.isFinishing || activity.isDestroyed) PurchaseStepResult.Failure()
                        else {
                            tracker.track("initiate_pay", paymentProperties("initiated", "google_play", flow, source, packageId,
                                orderId, "sdk_launch", amount = price, currency = currency) + mapOf("af_content_id" to sku), session.userId)
                            val result = billing.purchase(activity, sku, session.userId, orderId)
                            when (result.data?.purchaseState) {
                                PurchaseState.PURCHASED -> PurchaseStepResult.Success(result.data.purchaseToken)
                                PurchaseState.PENDING -> PurchaseStepResult.Pending(result.data.purchaseToken)
                                else -> PurchaseStepResult.Failure(code = result.responseCode)
                            }
                        }
                    },
                    consumePurchase = { billing.consume(it).toPurchaseStep() },
                )
                val status = when (result) {
                    is ConsumablePurchaseResult.Success -> CoinPurchaseStatus.COMPLETED
                    is ConsumablePurchaseResult.Pending -> CoinPurchaseStatus.PENDING
                    is ConsumablePurchaseResult.Failure -> if (result.code == BillingResponseCode.CANCELLED) CoinPurchaseStatus.CANCELLED else CoinPurchaseStatus.FAILED
                    ConsumablePurchaseResult.InProgress -> CoinPurchaseStatus.BUSY
                }
                mutable.value = CoinPurchaseState(session.epoch, status, (result as? ConsumablePurchaseResult.Failure)?.stage, revision)
                when (result) {
                    is ConsumablePurchaseResult.Success -> {
                        report("success", "play_consumed")
                        // 历史投放兼容事件统一发往四端；不带 af_revenue，避免重复计入收入。
                        listOf("purchase_client", "purchase_client_buy", "purchase_client_buy_${sku.replace(Regex("[^A-Za-z0-9_]"), "_")}")
                            .forEach { name -> tracker.track(name, mapOf("af_content_id" to sku, "package_id" to packageId,
                                "af_order_id" to reportedOrder.orEmpty(), "entry_source" to source, "status" to "success"), session.userId,
                                "play:${reportedOrder}:$name") }
                    }
                    is ConsumablePurchaseResult.Failure -> report(if (result.code == BillingResponseCode.CANCELLED) "cancelled" else "failed",
                        result.stage.name.lowercase(java.util.Locale.ROOT), if (result.code == BillingResponseCode.CANCELLED) "user_cancelled" else result.stage.name.lowercase(java.util.Locale.ROOT))
                    is ConsumablePurchaseResult.Pending -> report("pending", "play_pending")
                    else -> Unit
                }
                if (result is ConsumablePurchaseResult.Success && sessions.current?.epoch == session.epoch) {
                    // 与参考相同，consume 后读取钱包；本地不加币，也不猜测后端订单状态。
                    try { wallet.refreshBalance(); wallet.loadRecords(refresh = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* 当前余额保留服务器最后一次结果，前台或手动刷新继续更新。 */ }
                }
            } catch (cancelled: CancellationException) {
                mutable.value = CoinPurchaseState(session.epoch, CoinPurchaseStatus.PENDING, revision = revision)
                throw cancelled
            }
            catch (_: Exception) { mutable.value = CoinPurchaseState(session.epoch, CoinPurchaseStatus.FAILED, revision = revision); report("unknown", "unexpected", "unexpected_error") }
            finally { lease.close() }
        }
    }
}
