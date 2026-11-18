package yumo.achat.app.ui.imagevideo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShortDramaViewModelRetentionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun returningFromBillingDoesNotReapplyConsumedEntryEpisode() {
        val gateway=PlayerScreenGateway()
        compose.activityRule.scenario.onActivity { activity ->
            val vm=ViewModelProvider(activity)[ShortDramaPlayerViewModel::class.java]
            val controller=vm.bind("owner","user","drama",gateway)
            vm.surface(controller,"player",true)
            vm.openRequest(controller,"request-1","drama","episode-3")
            controller.selectEpisode("episode-4")
            vm.surface(controller,"player",false)
            vm.openRequest(controller,"request-1","drama","episode-3")
            assertEquals("episode-4",controller.episode?.episodeId)
            vm.surface(controller,"player",true)
            assertEquals("episode-4",controller.episode?.episodeId)
            assertEquals(2,gateway.plays)
            vm.openRequest(controller,"request-2","drama","episode-3")
            assertEquals("episode-3",controller.episode?.episodeId)
            vm.release()
        }
    }
    @Test fun recreationRetainsGrantAndNeverReplaysTheEpisode() {
        val gateway = PlayerScreenGateway()
        lateinit var firstVm: ShortDramaPlayerViewModel
        lateinit var first: ShortDramaPlaybackController
        var changingConfiguration = false
        compose.activityRule.scenario.onActivity { activity ->
            activity.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) changingConfiguration = activity.isChangingConfigurations
            })
            firstVm = ViewModelProvider(activity)[ShortDramaPlayerViewModel::class.java]
            first = firstVm.bind("owner", "test-owner", "drama", gateway)
            firstVm.surface(first, "test-surface", true)
            first.start("drama")
        }
        compose.waitUntil(5_000) { first.media != null }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            val restoredVm = ViewModelProvider(activity)[ShortDramaPlayerViewModel::class.java]
            val restored = restoredVm.bind("owner", "test-owner", "drama", gateway)
            restored.start("drama")
            assertTrue(changingConfiguration)
            assertSame(firstVm, restoredVm); assertSame(first, restored)
            assertEquals(1, gateway.plays)
            restoredVm.release()
        }
    }
}
