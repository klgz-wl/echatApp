package yumo.achat.core.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyOrderRegistryTest {
    @Test
    fun `legacy recharge is owned by user and succeeds only once`() = runBlocking {
        val storage = InMemoryLegacyOrderStorage()
        val registry = LegacyOrderRegistry(storage)
        registry.register(orderId = "order-1", userId = "user-1")

        assertEquals(LegacyRechargeMatch.UNKNOWN, registry.recharge("order-1", "user-2"))
        assertEquals(LegacyRechargeMatch.FIRST, registry.recharge("order-1", "user-1"))
        assertEquals(LegacyRechargeMatch.DUPLICATE, registry.recharge("order-1", "user-1"))
    }

    @Test
    fun `wallet reconciliation claims only an owned pending legacy order`() = runBlocking {
        val registry = LegacyOrderRegistry(InMemoryLegacyOrderStorage())
        registry.register(orderId = "order-1", userId = "user-1")
        registry.register(orderId = "order-2", userId = "user-1")
        registry.register(orderId = "order-3", userId = "user-2")

        assertEquals(true, registry.hasPending(userId = "user-1"))

        assertEquals(
            "order-2",
            registry.rechargeFirstMatching(setOf("order-2", "order-3"), userId = "user-1"),
        )
        assertEquals(
            null,
            registry.rechargeFirstMatching(setOf("order-2", "order-3"), userId = "user-1"),
        )
        assertEquals(true, registry.hasPending(userId = "user-1"))
        registry.recharge("order-1", "user-1")
        assertEquals(false, registry.hasPending(userId = "user-1"))
    }
}

private class InMemoryLegacyOrderStorage : LegacyOrderStorage {
    private var orders = emptyList<LegacyPaymentOrder>()

    override suspend fun read(): List<LegacyPaymentOrder> = orders

    override suspend fun write(orders: List<LegacyPaymentOrder>) {
        this.orders = orders
    }
}
