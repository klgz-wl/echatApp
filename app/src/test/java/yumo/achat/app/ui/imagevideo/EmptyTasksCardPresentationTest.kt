package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyTasksCardPresentationTest {
    @Test
    fun `empty tasks card keeps the tilted figma panel treatment`() {
        assertEquals(-2f, emptyTasksCardRotationDegrees())
    }

    @Test
    fun `empty tasks card stays hidden while refreshing or showing an error`() {
        assertTrue(shouldShowEmptyTasksCard(taskCount = 0, isLoading = false, errorMessage = null))
        assertFalse(shouldShowEmptyTasksCard(taskCount = 0, isLoading = true, errorMessage = null))
        assertFalse(shouldShowEmptyTasksCard(taskCount = 0, isLoading = false, errorMessage = "Failed"))
        assertFalse(shouldShowEmptyTasksCard(taskCount = 1, isLoading = false, errorMessage = null))
    }
}
