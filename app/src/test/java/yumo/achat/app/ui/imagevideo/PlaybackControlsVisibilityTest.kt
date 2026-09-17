package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlsVisibilityTest {
    @Test
    fun `shows controls while paused so playback can resume`() {
        assertTrue(shouldShowPlaybackControls(isPlaying = false, controlsRevealed = false))
    }

    @Test
    fun `hides controls while playing by default`() {
        assertFalse(shouldShowPlaybackControls(isPlaying = true, controlsRevealed = false))
    }

    @Test
    fun `shows controls while playing after user reveals them`() {
        assertTrue(shouldShowPlaybackControls(isPlaying = true, controlsRevealed = true))
    }
}
