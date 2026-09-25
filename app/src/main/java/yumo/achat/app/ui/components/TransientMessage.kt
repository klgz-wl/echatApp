package yumo.achat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import yumo.achat.app.ui.theme.AchatCyan
import yumo.achat.app.ui.theme.AchatPink

internal const val TRANSIENT_MESSAGE_DURATION_MILLIS = 3_000L

internal enum class TransientMessageTone {
    Neutral,
    Success,
    Error,
}

internal data class TransientMessage(
    val id: Long,
    val text: String,
    val tone: TransientMessageTone = TransientMessageTone.Neutral,
)

internal data class TransientMessageVisualStyle(
    val fontSizeSp: Int,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val bold: Boolean,
)

internal fun transientMessageVisualStyle(tone: TransientMessageTone): TransientMessageVisualStyle =
    if (tone == TransientMessageTone.Error) {
        TransientMessageVisualStyle(
            fontSizeSp = 14,
            horizontalPaddingDp = 16,
            verticalPaddingDp = 10,
            bold = true,
        )
    } else {
        TransientMessageVisualStyle(
            fontSizeSp = 14,
            horizontalPaddingDp = 12,
            verticalPaddingDp = 6,
            bold = false,
        )
    }

internal suspend fun dismissTransientMessageAfterDelay(
    message: TransientMessage?,
    delayMillis: Long = TRANSIENT_MESSAGE_DURATION_MILLIS,
    currentMessage: () -> TransientMessage?,
    clearMessage: () -> Unit,
) {
    if (message == null) return

    delay(delayMillis)
    if (currentMessage()?.id == message.id) {
        clearMessage()
    }
}

@Composable
internal fun TransientMessageHost(
    message: TransientMessage?,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
    durationMillis: Long = TRANSIENT_MESSAGE_DURATION_MILLIS,
) {
    val latestMessage by rememberUpdatedState(message)

    LaunchedEffect(message?.id, durationMillis) {
        dismissTransientMessageAfterDelay(
            message = message,
            delayMillis = durationMillis,
            currentMessage = { latestMessage },
            clearMessage = { message?.id?.let(onDismiss) },
        )
    }

    val visibleMessage = message ?: return
    val accent = when (visibleMessage.tone) {
        TransientMessageTone.Neutral -> Color.White.copy(alpha = 0.2f)
        TransientMessageTone.Success -> AchatCyan.copy(alpha = 0.56f)
        TransientMessageTone.Error -> AchatPink.copy(alpha = 0.62f)
    }
    val visualStyle = transientMessageVisualStyle(visibleMessage.tone)
    Text(
        text = visibleMessage.text,
        color = Color.White,
        fontSize = visualStyle.fontSizeSp.sp,
        fontWeight = if (visualStyle.bold) FontWeight.Bold else FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB2080B12))
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .padding(
                horizontal = visualStyle.horizontalPaddingDp.dp,
                vertical = visualStyle.verticalPaddingDp.dp,
            ),
    )
}
