package com.vexora.core.payment

import android.content.Context
import com.vexora.core.auth.Session
import com.vexora.core.billing.BillingConfiguration
import com.vexora.core.wallet.WalletRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class LegacyPaymentOrder(val orderId: String, val userId: String, val notified: Boolean = false)
enum class LegacyRechargeMatch { UNKNOWN, FIRST, DUPLICATE }
interface LegacyOrderStorage {
    suspend fun read(): List<LegacyPaymentOrder>
    suspend fun write(orders: List<LegacyPaymentOrder>)
}

/** 与新流程分开保存归属记录，切换配置和消费清理后仍能识别迟到的到账通知。 */
@Singleton
class PreferenceLegacyOrderStorage @Inject constructor(@ApplicationContext context: Context,
    config: PaymentConfiguration, private val json: Json) : LegacyOrderStorage {
    private val preferences = context.getSharedPreferences(config.storageName, Context.MODE_PRIVATE)
    override suspend fun read(): List<LegacyPaymentOrder> = withContext(Dispatchers.IO) {
        preferences.getString("legacy_orders", null)?.let { json.decodeFromString<List<LegacyPaymentOrder>>(it) } ?: emptyList()
    }
    override suspend fun write(orders: List<LegacyPaymentOrder>) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("legacy_orders", json.encodeToString(orders)).commit()) { "旧支付订单归属保存失败" }
    }
}

@Singleton
class LegacyOrderRegistry @Inject constructor(private val storage: LegacyOrderStorage) {
    private val lock = Mutex()
    suspend fun register(orderId: String, userId: String) = lock.withLock {
        val records = storage.read()
        if (records.none { it.orderId == orderId && it.userId == userId })
            storage.write(records + LegacyPaymentOrder(orderId, userId))
    }
    suspend fun recharge(orderId: String?, userId: String): LegacyRechargeMatch = lock.withLock {
        if (orderId == null) return@withLock LegacyRechargeMatch.UNKNOWN
        val records = storage.read()
        val record = records.firstOrNull { it.orderId == orderId && it.userId == userId }
            ?: return@withLock LegacyRechargeMatch.UNKNOWN
        if (record.notified) LegacyRechargeMatch.DUPLICATE else {
            storage.write(records.map { if (it == record) it.copy(notified = true) else it })
            LegacyRechargeMatch.FIRST
        }
    }
}

/** 旧支付接口门面仅调用业务订单 API，不依赖 payment-service 的初始化、查单或事件接口。 */
@Singleton
class LegacyPaymentRepository @Inject constructor(private val wallet: WalletRepository,
    private val config: BillingConfiguration, private val registry: LegacyOrderRegistry) {
    suspend fun createOrder(productId: String, session: Session): String {
        val order = wallet.createOrder(productId, config.defaultTrigger, session)
        registry.register(order.orderId, session.userId)
        return order.orderId
    }
}
