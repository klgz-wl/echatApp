package com.zorv.core.wallet

import com.zorv.core.auth.Session
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.network.ServiceFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class WalletSnapshot(val epoch: String? = null, val balance: Long? = null,
    val products: List<CoinProduct> = emptyList(), val transactions: List<CoinTransaction> = emptyList(),
    val nextPage: Int = 1, val hasMore: Boolean = true)

/** 真实钱包不使用演示值；所有异步结果必须仍属于发起请求的会话。 */
@Singleton
class WalletRepository @Inject constructor(private val api: WalletApi,
    private val sessions: SessionCoordinator, private val config: WalletConfiguration) {
    private val mutable = MutableStateFlow(WalletSnapshot())
    val state = mutable.asStateFlow()
    private val balanceLock = Mutex()
    private val recordsLock = Mutex()
    private fun active(): Session {
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        mutable.update { if (it.epoch == session.epoch) it else WalletSnapshot(epoch = session.epoch) }
        return session
    }
    private fun publish(session: Session, change: (WalletSnapshot) -> WalletSnapshot) {
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        mutable.update {
            if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
            change(if (it.epoch == session.epoch) it else WalletSnapshot(epoch = session.epoch))
        }
    }
    fun clear() { mutable.value = WalletSnapshot() }
    suspend fun refreshBalance(): Long = balanceLock.withLock {
        val session = active()
        val result = api.currencies().requireData()
        if (result.userId != session.userId) throw ServiceFailure.InvalidResponse
        publish(session) { it.copy(balance = result.balance) }
        result.balance
    }
    suspend fun refreshProducts() {
        val session = active()
        val result = api.products(config.productType, "android", config.location).requireData().products
        if (result.any { it.id.isBlank() || it.type != config.productType || it.subscription })
            throw ServiceFailure.InvalidResponse
        publish(session) { it.copy(products = result.distinctBy(CoinProduct::id)) }
    }
    suspend fun createOrder(productId: String, trigger: String, session: Session): CreatedOrder {
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        // 平台参与支付订单归属校验，使用必填字段保证默认序列化配置也会发送。
        val order = api.createOrder(CreateOrderRequest(productId, trigger, platform = "android"), session).requireData()
        if (order.orderId.isBlank() || order.productId != productId) throw ServiceFailure.InvalidResponse
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        return order
    }
    suspend fun loadRecords(refresh: Boolean = false) = recordsLock.withLock {
        val session = active()
        val before = mutable.value
        if (!refresh && !before.hasMore) return@withLock
        val page = if (refresh) 1 else before.nextPage
        val result = api.transactions(page, config.pageSize).requireData()
        if (result.page != page || result.pageSize <= 0 || result.total < 0 || result.transactions.any { it.id.isBlank() })
            throw ServiceFailure.InvalidResponse
        publish(session) {
            val records = ((if (refresh) emptyList() else it.transactions) + result.transactions).distinctBy(CoinTransaction::id)
            it.copy(transactions = records, nextPage = page + 1,
                hasMore = result.transactions.isNotEmpty() && page.toLong() * result.pageSize < result.total)
        }
    }
}
