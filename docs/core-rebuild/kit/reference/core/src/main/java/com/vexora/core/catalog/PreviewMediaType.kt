package com.vexora.core.catalog

import java.net.URI
import java.util.Locale

/** preview_url 没有独立 MIME 字段，按不含查询参数的路径识别，避免把视频模板的 WebP 封面误判为视频。 */
fun previewMediaType(url: String, originalUrl: String, originalMimeType: String): String {
    val extension = runCatching { URI(url).path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) }.getOrDefault("")
    return when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "avif" -> "image/avif"
        else -> if (url == originalUrl) originalMimeType else ""
    }
}
