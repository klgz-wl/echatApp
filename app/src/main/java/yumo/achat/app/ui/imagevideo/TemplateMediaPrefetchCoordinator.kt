package yumo.achat.app.ui.imagevideo

import android.content.Context
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal const val TemplatePreviewWidthPx = 720
internal const val TemplatePreviewHeightPx = 1280

internal fun interface TemplateMediaPrefetchHandle {
    fun cancel()
}

internal interface TemplateMediaPrefetchCoordinator {
    fun prefetchVideoPrefixes(context: Context, targets: List<TemplateVideoPreloadTarget>): TemplateMediaPrefetchHandle
    fun prefetchImages(context: Context, urls: List<String>): TemplateMediaPrefetchHandle
}

internal object DefaultTemplateMediaPrefetchCoordinator : TemplateMediaPrefetchCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun prefetchVideoPrefixes(context: Context, targets: List<TemplateVideoPreloadTarget>): TemplateMediaPrefetchHandle {
        val session = TemplateVideoPreloadSession()
        val job = scope.launch {
            TemplateVideoCache.preload(context.applicationContext, targets, session)
        }
        return TemplateMediaPrefetchHandle {
            session.cancel()
            job.cancel()
        }
    }

    override fun prefetchImages(context: Context, urls: List<String>): TemplateMediaPrefetchHandle {
        val appContext = context.applicationContext
        val requests: List<Disposable> = urls.map { url ->
            appContext.imageLoader.enqueue(
                ImageRequest.Builder(appContext)
                    .data(url)
                    .size(width = TemplatePreviewWidthPx, height = TemplatePreviewHeightPx)
                    .scale(Scale.FILL)
                    .build(),
            )
        }
        return TemplateMediaPrefetchHandle { requests.forEach { it.dispose() } }
    }
}
