package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TemplateMediaPreloaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changingTargetsAndLeavingCompositionCancelsPrefetchHandles() {
        val coordinator = FakeCoordinator()
        var videoTargets by mutableStateOf(listOf(TemplateVideoPreloadTarget("video-a", 1)))
        var visible by mutableStateOf(true)

        composeRule.setContent {
            if (visible) {
                TemplateMediaPreloader(
                    videoTargets = videoTargets,
                    imageUrls = listOf("image-a"),
                    coordinator = coordinator,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { videoTargets = listOf(TemplateVideoPreloadTarget("video-b", 1)) }
        composeRule.waitForIdle()
        assertTrue(coordinator.handles.take(2).all { it.cancelled })

        composeRule.runOnUiThread { visible = false }
        composeRule.waitForIdle()
        assertTrue(coordinator.handles.all { it.cancelled })
        assertEquals(listOf("video-a", "video-b"), coordinator.videoCalls.flatten().map { it.url })
    }

    private class FakeCoordinator : TemplateMediaPrefetchCoordinator {
        val videoCalls = mutableListOf<List<TemplateVideoPreloadTarget>>()
        val handles = mutableListOf<FakeHandle>()

        override fun prefetchVideoPrefixes(
            context: Context,
            targets: List<TemplateVideoPreloadTarget>,
        ): TemplateMediaPrefetchHandle {
            videoCalls += targets
            return FakeHandle().also(handles::add)
        }

        override fun prefetchImages(context: Context, urls: List<String>): TemplateMediaPrefetchHandle =
            FakeHandle().also(handles::add)
    }

    private class FakeHandle : TemplateMediaPrefetchHandle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }
}
