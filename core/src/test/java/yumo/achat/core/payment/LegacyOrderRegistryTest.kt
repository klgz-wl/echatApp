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
}

private class InMemoryLegacyOrderStorage : LegacyOrderStorage {
    private var orders = emptyList<LegacyPaymentOrder>()

    override suspend fun read(): List<LegacyPaymentOrder> = orders

    override suspend fun write(orders: List<LegacyPaymentOrder>) {
        this.orders = orders
    }
}
