package yumo.achat.app.ui.imagevideo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GeneratedVideoControlsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun videoTapImmediatelyPausesAndPausedRoundControlPersistsUntilResumed() {
        var playing by mutableStateOf(true)
        compose.setContent {MyTaskVideoPlayer("android.resource://yumo.achat.app/raw/drama_test_video","",playing,
            {playing=!playing},{error("local media failed")},Modifier.fillMaxSize())}
        compose.waitUntil(15000){compose.onAllNodesWithTag("result-video-frame").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("result-video-transport").assertDoesNotExist()
        compose.onNodeWithContentDescription("Result video player").performClick()
        compose.runOnIdle{assertFalse(playing)}
        compose.onNodeWithTag("result-video-transport").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("result-video-transport").assertIsDisplayed().performClick()
        compose.runOnIdle{assertTrue(playing)}
        compose.onNodeWithTag("result-video-transport").assertDoesNotExist()
        compose.onNodeWithTag("result-video-seek-track",useUnmergedTree=true).assertExists()
    }
    @Test fun accessibleSeekCommitsPositionWithoutResumingPausedVideo() {
        var playing by mutableStateOf(false)
        compose.setContent {MyTaskVideoPlayer("android.resource://yumo.achat.app/raw/drama_test_video","",playing,
            {playing=!playing},{error("local media failed")},Modifier.fillMaxSize())}
        compose.waitUntil(15000){!compose.onNodeWithTag("result-video-seek-control").fetchSemanticsNode().config
            .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
        compose.onNodeWithTag("result-video-seek-control").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(.5f)}
        compose.waitUntil(5000){compose.onNodeWithTag("result-video-seek-control").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current>.3f}
        compose.runOnIdle{assertFalse(playing)}
        compose.onNodeWithTag("result-video-transport").assertIsDisplayed()
    }
    @Test fun generatedVideoUsesSharedMuteAndSpeedControlsWithoutLosingPausedState() {
        var playing by mutableStateOf(false)
        compose.setContent {MyTaskVideoPlayer("android.resource://yumo.achat.app/raw/drama_test_video","",playing,
            {playing=!playing},{error("local media failed")},Modifier.fillMaxSize())}
        compose.onNodeWithContentDescription("Mute").performClick()
        compose.onNodeWithTag("drama-audio-muted",useUnmergedTree=true).assertExists()
        compose.onNodeWithContentDescription("Playback speed").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.onNodeWithContentDescription("Playback speed").performClick()
        compose.onNodeWithTag("playback-speed-1.5",useUnmergedTree=true).assertIsSelected()
        compose.runOnIdle{assertFalse(playing)}
    }
}
