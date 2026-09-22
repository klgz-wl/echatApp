package yumo.achat.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualGenerationTaskStatusTest {
    @Test
    fun `succeeded and failed are finished statuses`() {
        assertTrue(isVisualGenerationFinished("succeeded"))
        assertTrue(isVisualGenerationFinished("failed"))
        assertFalse(isVisualGenerationFinished("processing"))
        assertFalse(isVisualGenerationFinished("pending"))
    }

    @Test
    fun `poll interval defaults to three seconds`() {
        assertEquals(3, visualGenerationPollIntervalSeconds(null))
        assertEquals(3, visualGenerationPollIntervalSeconds(0))
        assertEquals(7, visualGenerationPollIntervalSeconds(7))
    }
}
