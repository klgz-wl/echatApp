package yumo.achat.app.ui.login

import android.os.SystemClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import yumo.achat.app.R

internal fun deleteReady(openedAtMillis: Long, nowMillis: Long): Boolean = nowMillis - openedAtMillis >= 3_000L

internal fun deleteSecondsRemaining(openedAtMillis: Long, nowMillis: Long): Int =
    ((3_000L - (nowMillis - openedAtMillis)).coerceAtLeast(0L) + 999L).div(1_000L).toInt()

@Composable
fun DeleteAccountDialog(
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val openedAt = remember { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(openedAt) }
    LaunchedEffect(openedAt) {
        while (true) {
            delay(100)
            now = SystemClock.elapsedRealtime()
        }
    }
    val remaining = deleteSecondsRemaining(openedAt, now)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.account_delete_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.account_delete_message))
                if (error != null) Text(error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.account_delete_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && deleteReady(openedAt, now)) {
                Text(
                    when {
                        busy -> stringResource(R.string.account_delete_busy)
                        remaining > 0 -> stringResource(R.string.account_delete_countdown, remaining)
                        else -> stringResource(R.string.account_delete_confirm)
                    },
                )
            }
        },
    )
}
