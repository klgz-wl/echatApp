package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class MeSystemModulePresentationTest {
    @Test
    fun `system module badges match the visual reference`() {
        val rows = meSystemModulePresentations()

        assertEquals("LOGS", rows[0].badge)
        assertEquals("FEED", rows[1].badge)
        assertEquals("USER_ID", rows[2].badge)
    }
}
