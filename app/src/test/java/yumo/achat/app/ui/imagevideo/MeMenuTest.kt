package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class MeMenuTest {
    @Test fun `Me modules match profile design order`() {
        assertEquals(listOf("Creations", "WatchHistory", "Feedback", "DiamondHistory", "Privacy", "Terms", "Version"),
            meMenuEntries().map { it.name })
    }
    @Test fun `diamond records honor expense and income types`() {
        assertEquals("-14.00", diamondAmountLabel(14, "expense"))
        assertEquals("+14.00", diamondAmountLabel(-14, "income"))
        assertEquals("-2147483648.00", diamondAmountLabel(Int.MIN_VALUE, "expense"))
    }
    @Test fun `record time uses design format and preserves unknown dates`() {
        assertEquals("17:55 09/04/2026", diamondRecordTime("2026-04-09T17:55:00Z"))
        assertEquals("unknown", diamondRecordTime("unknown"))
    }
}
