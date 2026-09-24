package yumo.achat.core.billing

import org.junit.Assert.assertEquals
import org.junit.Test

class CoinPurchaseStatusTest {
    @Test
    fun `backend owned fulfillment keeps accepted Play purchase pending`() {
        assertEquals(
            CoinPurchaseStatus.PENDING,
            resolvedCoinPurchaseStatus(
                ConsumablePurchaseResult.Success("product"),
                backendOwnedFulfillment = true,
            ),
        )
        assertEquals(
            CoinPurchaseStatus.COMPLETED,
            resolvedCoinPurchaseStatus(
                ConsumablePurchaseResult.Success("product"),
                backendOwnedFulfillment = false,
            ),
        )
    }
}
