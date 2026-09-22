package yumo.achat.core.billing

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级购买准入。
 *
 * Google Play 同一时间只允许一条购买链路。所有 IN_APP 与 SUBS 入口都必须在连接、
 * 查询商品和创建后端订单之前获取租约，避免跨页面并发产生无效订单。
 */
@Singleton
class BillingPurchaseAdmission @Inject constructor() {
    private val occupied = AtomicBoolean(false)

    fun tryAcquire(): BillingPurchaseLease? =
        if (occupied.compareAndSet(false, true)) BillingPurchaseLease(occupied) else null
}

class BillingPurchaseLease internal constructor(
    private val occupied: AtomicBoolean,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) occupied.set(false)
    }
}
