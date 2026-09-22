package com.vexora.app.analytics

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vexora.core.analytics.EventTracker
import com.vexora.core.analytics.NoOpEventTracker
import kotlinx.coroutines.awaitCancellation

/** Debug 设计预览默认不发送真实埋点。 */
val LocalEventTracker = staticCompositionLocalOf<EventTracker> { NoOpEventTracker }

@Composable
fun TrackPage(name: String, parameters: Map<String, Any> = emptyMap()) {
    val tracker = LocalEventTracker.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(name, parameters, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            tracker.track("page_view", parameters + mapOf("page_name" to name))
            if (name == "purchase") tracker.track("view_store", parameters)
            awaitCancellation()
        }
    }
}
