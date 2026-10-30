package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletTransactionAmountTest {
    @Test fun `generation expenses display deductions even when backend amount is positive`() {
        assertEquals("-15", formatWalletTransactionAmount(15, "expense"))
        assertEquals("-32", formatWalletTransactionAmount(32, "expense"))
        assertEquals(-15L, walletTransactionDisplayAmount(15, "expense"))
    }

    @Test fun `purchase income and generation refunds display additions`() {
        assertEquals("+100", formatWalletTransactionAmount(100, "income"))
        assertEquals("+16", formatWalletTransactionAmount(16, "income"))
        assertEquals(16L, walletTransactionDisplayAmount(16, "income"))
    }

    @Test fun `known direction normalizes signed values without integer overflow`() {
        assertEquals("-15", formatWalletTransactionAmount(-15, "expense"))
        assertEquals("+16", formatWalletTransactionAmount(-16, "income"))
        assertEquals("-2147483648", formatWalletTransactionAmount(Int.MIN_VALUE, "expense"))
        assertEquals("+2147483648", formatWalletTransactionAmount(Int.MIN_VALUE, "income"))
    }

    @Test fun `unknown direction preserves the numeric sign and zero stays neutral`() {
        assertEquals("-7", formatWalletTransactionAmount(-7, "unknown"))
        assertEquals("+7", formatWalletTransactionAmount(7, ""))
        assertEquals("+0", formatWalletTransactionAmount(0, "expense"))
    }
}
