package yumo.achat.core.payment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable enum class PaymentStage { CREATING, INITIALIZING, OFFICIAL_READY, OFFICIAL_LAUNCHED, OFFICIAL_CONSUMED, CHECKOUT, VERIFYING, AWAITING_FULFILLMENT, UNCERTAIN, CLOSED, TIMED_OUT, FAILED, SUCCESS, ACCESS_DENIED }
@Serializable data class PaymentRecord(
    val key: String, val userId: String, val productId: String, val source: String,
    val orderId: String? = null, val initialized: InitializedPayment? = null,
    val stage: PaymentStage = PaymentStage.CREATING, val openedAt: Long? = null,
    val deadline: Long? = null, val successShownAt: Long? = null, val successAcknowledged: Boolean = false,
    val error: String? = null,
    val quotePrice: Double? = null, val quoteCurrency: String? = null,
    val quoteSource: String = "catalog_quote",
    val obfuscatedAccountId: String? = null,
    val obfuscatedProfileId: String? = null,
    val officialFallbackProductId: String? = null,
) {
    val thirdParty get() = initialized?.channelType == "third_party"
    // ACCESS_DENIED 只表示本客户端不得继续使用该订单，不代表服务器取消、退款或已完成。
    val unresolved get() = stage !in setOf(PaymentStage.SUCCESS, PaymentStage.FAILED, PaymentStage.OFFICIAL_CONSUMED, PaymentStage.ACCESS_DENIED)
    fun expired(now: Long) = deadline?.let { now >= it || (openedAt != null && now < openedAt) } ?: false
    override fun toString() = "PaymentRecord(订单及支付链接已隐藏, stage=$stage)"
}
interface PaymentStorage {
    suspend fun read(): List<PaymentRecord>
    suspend fun write(records: List<PaymentRecord>)
}
@Singleton
class PreferencePaymentStorage @Inject constructor(@ApplicationContext context: Context,
    config: PaymentConfiguration, private val json: Json) : PaymentStorage {
    private val preferences = context.getSharedPreferences(config.storageName, Context.MODE_PRIVATE)
    override suspend fun read(): List<PaymentRecord> = withContext(Dispatchers.IO) {
        preferences.getString("orders", null)?.let { json.decodeFromString<List<PaymentRecord>>(it) } ?: emptyList()
    }
    override suspend fun write(records: List<PaymentRecord>) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("orders", json.encodeToString(records)).commit()) { "支付记录保存失败" }
    }
}
fun interface PaymentClock { fun now(): Long }
