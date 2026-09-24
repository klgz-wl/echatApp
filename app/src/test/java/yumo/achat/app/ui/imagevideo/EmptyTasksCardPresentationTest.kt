package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyTasksCardPresentationTest {
    @Test
    fun `empty tasks card keeps the tilted figma panel treatment`() {
        assertEquals(-2f, emptyTasksCardRotationDegrees())
    }
}
