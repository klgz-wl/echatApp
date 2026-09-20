package yumo.achat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AchatColorScheme = darkColorScheme(
    primary = AchatCyan,
    secondary = AchatPink,
    background = AchatDeepNavy,
    surface = AchatSurface,
)

@Composable
fun AchatTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AchatColorScheme,
        typography = AchatTypography,
        content = content,
    )
}
