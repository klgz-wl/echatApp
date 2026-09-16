package yumo.achat.app.ui.imagevideo

import yumo.achat.app.data.backend.VisualTemplate

internal sealed interface TemplatePreviewMedia {
    data object LocalPlaceholder : TemplatePreviewMedia
    data class RemoteImage(val url: String) : TemplatePreviewMedia
}

internal fun VisualTemplate?.toPreviewMedia(): TemplatePreviewMedia {
    if (this == null) {
        return TemplatePreviewMedia.LocalPlaceholder
    }

    return if (mimeType.startsWith("image/") && fileUrl.isNotBlank()) {
        TemplatePreviewMedia.RemoteImage(fileUrl)
    } else {
        TemplatePreviewMedia.LocalPlaceholder
    }
}
