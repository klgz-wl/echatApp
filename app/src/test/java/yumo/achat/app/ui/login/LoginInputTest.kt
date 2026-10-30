package yumo.achat.app.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginInputTest {
    @Test fun `account strips spaces and is limited to fifty characters`() {
        assertEquals("Ab!23", cleanAccountInput(" A b ! 2 3 "))
        assertEquals(50, cleanAccountInput("a".repeat(60)).length)
    }

    @Test fun `password strips spaces and is limited to eighteen characters`() {
        assertEquals("aB!234", cleanPasswordInput(" a B ! 2 3 4 "))
        assertEquals(18, cleanPasswordInput("p".repeat(25)).length)
    }

    @Test fun `account and password boundaries are enforced`() {
        assertFalse(validLoginInput("ab", "123456"))
        assertFalse(validLoginInput("abc", "12345"))
        assertFalse(validLoginInput("abc", "1".repeat(19)))
        assertTrue(validLoginInput("a!3", "a!3456"))
        assertTrue(validLoginInput("a".repeat(50), "b".repeat(18)))
    }
}
