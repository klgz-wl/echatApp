package yumo.achat.app.ui.imagevideo

import androidx.annotation.OptIn
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

internal fun uploadPreviewContentScale(): ContentScale = ContentScale.Fit

internal fun uploadScreenContentShouldScroll(): Boolean = true

@OptIn(UnstableApi::class)
internal fun uploadPreviewVideoResizeMode(): Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
