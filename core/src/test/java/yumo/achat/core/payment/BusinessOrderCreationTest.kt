package yumo.achat.core.payment

import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.wallet.CreatedOrder

class BusinessOrderCreationTest {
    @Test fun oldRecordsRetainServiceCreationAndUnknownBusinessCreationCannotRepeat() {
        assertEquals(OrderCreationSource.PaymentService, orderCreationSource(null))
        assertEquals(OrderCreationSource.Business, orderCreationSource("business"))
        assertNull(orderCreationSource("unknown"))
        assertTrue(canReplayOrderCreation(null, true))
        assertFalse(canReplayOrderCreation("business", true))
        assertTrue(canReplayOrderCreation("business", false))
    }
    @Test fun businessOrderPreservesPlayIdentitiesAndValidatesProduct() {
        val created = CreatedOrder("order", "product", "pending", "account-hash", "profile-hash")
        val mapped = businessPaymentOrder(created, "product", "real-owner")
        assertEquals("account-hash", mapped.obfuscatedAccountId)
        assertEquals("profile-hash", mapped.obfuscatedProfileId)
        assertEquals("order", mapped.orderId)
        assertThrows(PaymentFailure.InvalidResponse::class.java) { businessPaymentOrder(created, "another", "real-owner") }
    }
    @Test fun missingOptionalPlayIdentitiesUseFrozenLegacyDefaults() {
        val mapped = businessPaymentOrder(CreatedOrder("order", "product", "pending"), "product", "owner")
        assertEquals("owner", mapped.obfuscatedAccountId)
        assertEquals("order", mapped.obfuscatedProfileId)
    }
}
