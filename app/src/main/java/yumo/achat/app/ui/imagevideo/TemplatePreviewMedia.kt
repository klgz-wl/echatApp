package yumo.achat.app.ui.imagevideo

import yumo.achat.core.backend.VisualTemplate
import androidx.compose.ui.layout.ContentScale
import coil.size.Scale

internal sealed interface TemplatePreviewMedia {
    data object LocalPlaceholder : TemplatePreviewMedia
    data object Unavailable : TemplatePreviewMedia
    data class RemoteImage(val url: String) : TemplatePreviewMedia
    data class RemoteVideo(val url: String, val posterUrl: String = "") : TemplatePreviewMedia
}

internal fun VisualTemplate?.toPreviewMedia(): TemplatePreviewMedia {
    if (this == null) {
        return TemplatePreviewMedia.Unavailable
    }

    return when {
        fileUrl.isBlank() -> TemplatePreviewMedia.Unavailable
        mimeType.startsWith("image/") -> TemplatePreviewMedia.RemoteImage(previewUrl.ifBlank { fileUrl })
        mimeType.startsWith("video/") -> TemplatePreviewMedia.RemoteVideo(fileUrl, previewUrl)
        else -> TemplatePreviewMedia.Unavailable
    }
}

internal fun TemplatePreviewMedia.isUsableGenerationMedia(): Boolean =
    this is TemplatePreviewMedia.RemoteImage || this is TemplatePreviewMedia.RemoteVideo

internal fun coilScaleForContentScale(contentScale: ContentScale): Scale =
    if (contentScale == ContentScale.Fit || contentScale == ContentScale.Inside) Scale.FIT else Scale.FILL
