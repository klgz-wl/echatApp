package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.ui.components.TransientMessage
import yumo.achat.app.ui.components.TransientMessageTone
import yumo.achat.app.ui.components.dismissTransientMessageAfterDelay
import yumo.achat.app.ui.components.transientMessageVisualStyle

class TransientMessageTest {
    @Test
    fun errorMessageUsesReadableEmphasizedTypography() {
        val error = transientMessageVisualStyle(TransientMessageTone.Error)
        val success = transientMessageVisualStyle(TransientMessageTone.Success)

        assertEquals(14, error.fontSizeSp)
        assertEquals(true, error.bold)
        assertEquals(16, error.horizontalPaddingDp)
        assertEquals(10, error.verticalPaddingDp)
        assertEquals(14, success.fontSizeSp)
    }

    @Test
    fun clearsCurrentMessageAfterDelay() = runBlocking {
        var currentMessage: TransientMessage? = TransientMessage(
            id = 1L,
            text = "Insufficient diamond balance",
            tone = TransientMessageTone.Error,
        )

        dismissTransientMessageAfterDelay(
            message = currentMessage,
            delayMillis = 1L,
            currentMessage = { currentMessage },
            clearMessage = { currentMessage = null },
        )

        assertEquals(null, currentMessage)
    }

    @Test
    fun repeatedTextWithNewIdIsNotClearedByOlderMessage() = runBlocking {
        val olderMessage = TransientMessage(id = 1L, text = "Reached the end")
        var currentMessage: TransientMessage? = olderMessage

        val dismissal = launch {
            dismissTransientMessageAfterDelay(
                message = olderMessage,
                delayMillis = 20L,
                currentMessage = { currentMessage },
                clearMessage = { currentMessage = null },
            )
        }
        delay(1L)
        currentMessage = TransientMessage(id = 2L, text = "Reached the end")
        dismissal.join()

        assertEquals(2L, currentMessage?.id)
    }
}
