package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackSubmissionTest {
    @Test
    fun `simulated feedback submit shows local thank you message`() {
        assertEquals("Thanks for your feedback", simulatedFeedbackAcknowledgement())
    }

    @Test
    fun `simulated feedback can submit text or attachment`() {
        assertFalse(canSubmitSimulatedFeedback("   ", hasAttachment = false))
        assertTrue(canSubmitSimulatedFeedback("The layout looks broken", hasAttachment = false))
        assertTrue(canSubmitSimulatedFeedback("", hasAttachment = true))
    }
}
