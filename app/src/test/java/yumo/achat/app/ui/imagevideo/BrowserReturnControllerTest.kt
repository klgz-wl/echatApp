package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test

class BrowserReturnControllerTest {
    @Test fun `initial resume ignored but recreated observer consumes departed browser once`() {
        val owner = BrowserReturnController()
        owner.launched("epoch", "order")
        assertNull(owner.resumed("epoch"))
        owner.paused()
        // The Activity observer is replaced, while this ViewModel-owned controller survives.
        assertEquals("order", owner.resumed("epoch"))
        assertNull(owner.resumed("epoch"))
    }

    @Test fun `browser return from old session cannot close current checkout`() {
        val owner = BrowserReturnController()
        owner.launched("old", "order"); owner.paused()
        assertNull(owner.resumed("new"))
        assertNull(owner.resumed("old"))
    }
}
