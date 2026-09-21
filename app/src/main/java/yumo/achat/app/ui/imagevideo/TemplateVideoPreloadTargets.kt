package yumo.achat.app.ui.imagevideo

import yumo.achat.app.data.backend.VisualTemplate

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

internal fun shouldPlayVideo(requestedPlaying: Boolean, lifecycleStarted: Boolean): Boolean =
    requestedPlaying && lifecycleStarted
