package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class MyTaskVideoPlaybackStateTest {
    @Test
    fun playbackProgressIsClampedToPlayableRange() {
        assertEquals(0f, myTaskPlaybackProgress(positionMs = -500L, durationMs = 10_000L))
        assertEquals(0.25f, myTaskPlaybackProgress(positionMs = 2_500L, durationMs = 10_000L))
        assertEquals(1f, myTaskPlaybackProgress(positionMs = 12_000L, durationMs = 10_000L))
        assertEquals(0f, myTaskPlaybackProgress(positionMs = 500L, durationMs = 0L))
    }

    @Test
    fun seekPositionUsesClampedProgressAndKnownDuration() {
        assertEquals(0L, myTaskSeekPosition(progress = -0.5f, durationMs = 20_000L))
        assertEquals(10_000L, myTaskSeekPosition(progress = 0.5f, durationMs = 20_000L))
        assertEquals(20_000L, myTaskSeekPosition(progress = 1.5f, durationMs = 20_000L))
        assertEquals(0L, myTaskSeekPosition(progress = 0.5f, durationMs = 0L))
    }

    @Test
    fun playbackTimeUsesCompactMinutesAndSeconds() {
        assertEquals("0:00", formatMyTaskPlaybackTime(-1L))
        assertEquals("0:09", formatMyTaskPlaybackTime(9_999L))
        assertEquals("1:05", formatMyTaskPlaybackTime(65_000L))
        assertEquals("61:01", formatMyTaskPlaybackTime(3_661_000L))
    }
}
