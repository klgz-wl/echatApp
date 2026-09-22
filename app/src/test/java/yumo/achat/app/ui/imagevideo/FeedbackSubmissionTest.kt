package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.layout.ContentScale
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

    @Test
    fun `simulated feedback supports multiple image attachments`() {
        assertEquals(0, feedbackAttachmentCount(cameraCount = 0, libraryCount = 0))
        assertEquals(3, feedbackAttachmentCount(cameraCount = 1, libraryCount = 2))
        assertTrue(shouldShowFeedbackAttachmentPreview(feedbackAttachmentCount(cameraCount = 1, libraryCount = 2)))
    }

    @Test
    fun `simulated feedback shows preview only after image is attached`() {
        assertFalse(shouldShowFeedbackAttachmentPreview(attachmentCount = 0))
        assertTrue(shouldShowFeedbackAttachmentPreview(attachmentCount = 1))
    }

    @Test
    fun `feedback preview uses taller fit card instead of cropped strip`() {
        assertEquals(160, feedbackAttachmentPreviewHeightDp())
        assertEquals(ContentScale.Fit, feedbackAttachmentPreviewContentScale())
    }
}
