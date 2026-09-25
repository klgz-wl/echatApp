package yumo.achat.app.ui.imagevideo

import yumo.achat.core.backend.VisualTemplate

internal const val TemplateAdjacentVideoPreloadBytes = 768L * 1024L
internal const val TemplateCurrentVideoPreloadBytes = 2L * 1024L * 1024L

internal data class TemplateVideoPreloadTarget(
    val url: String,
    val bytes: Long,
)

internal fun List<VisualTemplate>.videoPreviewPreloadTargets(selectedIndex: Int): List<TemplateVideoPreloadTarget> {
    if (isEmpty()) {
        return emptyList()
    }

    return listOf(
        selectedIndex to TemplateCurrentVideoPreloadBytes,
        selectedIndex + 1 to TemplateAdjacentVideoPreloadBytes,
        selectedIndex - 1 to TemplateAdjacentVideoPreloadBytes,
    ).mapNotNull { (index, bytes) ->
        getOrNull(index)
            ?.takeIf { template -> template.mimeType.startsWith("video/") && template.fileUrl.isNotBlank() }
            ?.let { template -> TemplateVideoPreloadTarget(template.fileUrl, bytes) }
    }.distinctBy { it.url }
}

internal fun List<VisualTemplate>.nearbyVideoPreviewUrls(selectedIndex: Int): List<String> {
    if (isEmpty()) {
        return emptyList()
    }

    return listOf(selectedIndex + 1, selectedIndex - 1)
        .mapNotNull { index -> getOrNull(index) }
        .filter { template -> template.mimeType.startsWith("video/") && template.fileUrl.isNotBlank() }
        .map { template -> template.fileUrl }
        .distinct()
}

internal fun List<VisualTemplate>.nearbyImagePreviewUrls(selectedIndex: Int): List<String> {
    if (isEmpty()) return emptyList()

    return listOf(selectedIndex + 1, selectedIndex - 1)
        .mapNotNull { index -> getOrNull(index) }
        .map { template ->
            template.previewUrl.takeIf { it.isNotBlank() }
                ?: template.fileUrl.takeIf { template.mimeType.startsWith("image/") }
                .orEmpty()
        }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun templatePagerKey(templates: List<VisualTemplate>, page: Int): String = templates[page].id

internal fun shouldPlayTemplatePage(page: Int, currentPage: Int, requestedPlaying: Boolean): Boolean =
    requestedPlaying && page == currentPage

internal fun shouldShowVideoPoster(hasRenderedFirstFrame: Boolean): Boolean = !hasRenderedFirstFrame

internal fun shouldShowTemplateMediaLoading(
    hasRenderedRemoteContent: Boolean,
    hasMediaError: Boolean,
): Boolean = !hasRenderedRemoteContent && !hasMediaError

internal fun shouldPlayVideo(requestedPlaying: Boolean, lifecycleStarted: Boolean): Boolean =
    requestedPlaying && lifecycleStarted
