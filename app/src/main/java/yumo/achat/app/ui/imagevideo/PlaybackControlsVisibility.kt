package yumo.achat.app.ui.imagevideo

internal fun shouldShowPlaybackControls(
    isPlaying: Boolean,
    controlsRevealed: Boolean,
): Boolean = !isPlaying || controlsRevealed
