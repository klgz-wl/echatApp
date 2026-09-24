package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import yumo.achat.core.analytics.EventTracker

internal fun analyticsPageName(
    destination: ImageToVideoDestination,
    selectedNavigation: Int,
    hasSelectedResult: Boolean,
): String? = when (destination) {
    ImageToVideoDestination.Templates -> when (selectedNavigation) {
        0 -> "video"
        1 -> "image"
        2 -> "purchase"
        3 -> "profile"
        else -> null
    }
    ImageToVideoDestination.UploadPhoto -> "generation"
    ImageToVideoDestination.MyTasks -> if (hasSelectedResult) "media" else "records"
    ImageToVideoDestination.EditName -> "settings"
    ImageToVideoDestination.Feedback -> null
}

@Composable
internal fun TrackAnalyticsPage(
    pageName: String?,
    tracker: EventTracker,
    userId: String?,
    parameters: Map<String, Any> = emptyMap(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pageName, parameters, lifecycle, tracker, userId) {
        val page = pageName ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            tracker.track("page_view", parameters + mapOf("page_name" to page), userId)
            if (page == "purchase") {
                tracker.track("view_store", parameters + mapOf("entry_source" to "main"), userId)
            }
            awaitCancellation()
        }
    }
}
