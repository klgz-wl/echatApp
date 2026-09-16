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
