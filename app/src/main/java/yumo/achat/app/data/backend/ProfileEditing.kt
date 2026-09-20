package yumo.achat.app.data.backend

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject

internal const val MAX_PROFILE_AVATAR_BYTES = 20 * 1024 * 1024

internal fun validatedProfileNickname(value: String): String {
    val normalized = value.trim()
    require(normalized.length in 2..50) { "Name must be between 2 and 50 characters" }
    return normalized
}

internal fun profileUpdateBody(
    nickname: String? = null,
    avatarUrl: String? = null,
): String = JSONObject().apply {
    nickname?.let { put("nickname", validatedProfileNickname(it)) }
    avatarUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
        put("avatar", url)
        put("avatar_large", url)
    }
}.also { body ->
    require(body.length() > 0) { "At least one profile field is required" }
}.toString()

internal fun profileAvatarUploadParts(
    fileName: String,
    contentType: String,
    bytes: ByteArray,
): List<MultipartFormData.Part> {
    require(bytes.size <= MAX_PROFILE_AVATAR_BYTES) { "Profile photo must be 20 MB or smaller" }
    return listOf(
    MultipartFormData.imagePart(
        fieldName = "file",
        fileName = fileName,
        contentType = contentType,
        bytes = bytes,
    ),
    MultipartFormData.textPart("upload_source", "profile"),
    )
}

internal fun validatedProfileAvatarContentType(contentType: String?): String = when {
    contentType.isNullOrBlank() -> "image/jpeg"
    contentType.startsWith("image/", ignoreCase = true) -> contentType
    else -> throw IllegalArgumentException("Selected file must be an image")
}

internal fun readProfileAvatarBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_PROFILE_AVATAR_BYTES) { "Profile photo must be 20 MB or smaller" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
