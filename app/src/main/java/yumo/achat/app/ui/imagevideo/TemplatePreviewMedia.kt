package yumo.achat.app.ui.imagevideo

import yumo.achat.app.data.backend.VisualTemplate

internal sealed interface TemplatePreviewMedia {
    data object LocalPlaceholder : TemplatePreviewMedia
    data class RemoteImage(val url: String) : TemplatePreviewMedia
    data class RemoteVideo(val url: String) : TemplatePreviewMedia
}

internal fun VisualTemplate?.toPreviewMedia(): TemplatePreviewMedia {
    if (this == null) {
        return TemplatePreviewMedia.LocalPlaceholder
    }

    return when {
        fileUrl.isBlank() -> TemplatePreviewMedia.LocalPlaceholder
        mimeType.startsWith("image/") -> TemplatePreviewMedia.RemoteImage(fileUrl)
        mimeType.startsWith("video/") -> TemplatePreviewMedia.RemoteVideo(fileUrl)
        else -> TemplatePreviewMedia.LocalPlaceholder
    }
}
