package yumo.achat.app.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountCountdownTest {
    @Test fun `delete remains unavailable for three seconds from opening`() {
        assertFalse(deleteReady(10_000, 12_999))
        assertTrue(deleteReady(10_000, 13_000))
    }

    @Test fun `remaining countdown rounds up`() {
        assertEquals(3, deleteSecondsRemaining(10_000, 10_000))
        assertEquals(2, deleteSecondsRemaining(10_000, 11_001))
        assertEquals(0, deleteSecondsRemaining(10_000, 13_000))
    }
}
