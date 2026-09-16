package yumo.achat.app.data.backend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AchatRepository(
    context: Context,
    private val client: AchatBackendClient = AchatBackendClient(),
) {
    private val appContext = context.applicationContext
    private val sessionStore = AchatSessionStore(appContext)

    suspend fun loadHomeData(): AchatHomeData = withContext(Dispatchers.IO) {
        val session = ensureSession()

        val profile = runCatching { client.userProfile(session.token) }.getOrNull()
        val currency = runCatching { client.userCurrency(session.token) }.getOrNull()
        val videoTemplates = runCatching { client.templates(session.token, "video") }.getOrDefault(emptyList())
        val imageTemplates = runCatching { client.templates(session.token, "image") }.getOrDefault(emptyList())

        AchatHomeData(
            session = session,
            profile = profile,
            currency = currency,
            videoTemplates = videoTemplates,
            imageTemplates = imageTemplates,
        )
    }

    suspend fun uploadSourceImage(uri: Uri): VisualResource = withContext(Dispatchers.IO) {
        val session = ensureSession()
        val resolver = appContext.contentResolver
        val contentType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        val fileName = resolver.displayName(uri) ?: defaultFileName(contentType)
        val bytes = resolver.openInputStream(uri)?.use { input -> input.readBytes() }
            ?: error("Unable to read selected image")
        val part = MultipartFormData.imagePart(
            fieldName = "image",
            fileName = fileName,
            contentType = contentType,
            bytes = bytes,
        )
        client.uploadVisualResource(session.token, part)
    }

    private fun ensureSession(): AuthSession =
        sessionStore.readSession() ?: client
            .anonymousLogin(sessionStore.deviceId())
            .also(sessionStore::saveSession)

    private fun android.content.ContentResolver.displayName(uri: Uri): String? =
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
            } else {
                null
            }
        }

    private fun defaultFileName(contentType: String): String {
        val extension = when (contentType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return "source-image.$extension"
    }
}
