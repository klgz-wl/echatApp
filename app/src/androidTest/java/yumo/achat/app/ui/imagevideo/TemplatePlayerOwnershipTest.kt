package yumo.achat.app.ui.imagevideo

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TemplatePlayerOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inactivePagesReleasePlayersWhileVisiblePausedPageRetainsPlayer() {
        var active by mutableStateOf(false)
        var playing by mutableStateOf(false)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            HeroCard(1, 3, 5, TemplatePreviewMedia.RemoteVideo("https://127.0.0.1:1/preview.mp4"),
                isPlaying = playing, videoPlaybackEnabled = active,
                onPlayToggle = {}, onPrevious = {}, onNext = {})
        }
        compose.runOnIdle { assertEquals(0, playerCount(root)); active = true; playing = true }
        compose.runOnIdle { assertEquals(1, playerCount(root)); playing = false }
        compose.runOnIdle { assertEquals(1, playerCount(root)); active = false }
        compose.runOnIdle { assertEquals(0, playerCount(root)) }
    }

    private fun playerCount(view: View): Int = (if (view is PlayerView) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { playerCount(view.getChildAt(it)) } else 0
}
