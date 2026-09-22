package yumo.achat.core.visual

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class GenerationConfiguration(val directory: String, val maxImageBytes: Long)
@Singleton
class PrivateGenerationStorage @Inject constructor(@ApplicationContext private val context: Context,
    private val config: GenerationConfiguration, private val json: Json) : GenerationStorage {
    private val directory get() = File(context.filesDir, config.directory).also { it.mkdirs() }
    private fun ownerKey(userId: String) = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun records(userId: String) = File(directory, "${ownerKey(userId)}.json")
    override suspend fun read(userId: String): List<GenerationRequest> = withContext(Dispatchers.IO) {
        val file = AtomicFile(records(userId))
        try { file.openRead().bufferedReader().use { json.decodeFromString<List<GenerationRequest>>(it.readText()) } }
        catch (error: java.io.FileNotFoundException) { if (file.baseFile.exists()) throw error else emptyList() }
    }
    override suspend fun write(userId: String, requests: List<GenerationRequest>) = withContext(Dispatchers.IO) {
        val file = AtomicFile(records(userId)); val out = file.startWrite()
        try { out.write(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GenerationRequest.serializer()), requests).toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    override fun photoFile(photo: SourcePhoto): File {
        require(photo.fileName == File(photo.fileName).name && !photo.fileName.contains(".."))
        return File(directory, photo.fileName)
    }
    override suspend fun clear(userId: String) {
        val requests = read(userId)
        withContext(Dispatchers.IO) {
            requests.forEach { photoFile(it.photo).delete() }
            val prefix = ownerKey(userId) + "-"
            directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { if (!it.delete() && it.exists()) throw java.io.IOException() }
            AtomicFile(records(userId)).delete()
        }
    }
    suspend fun prepare(uri: Uri, userId: String): SourcePhoto = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { prepare(it, userId) }
            ?: throw GenerationException(GenerationFailure.INVALID_IMAGE)
    }
    suspend fun prepare(input: java.io.InputStream, userId: String): SourcePhoto = withContext(Dispatchers.IO) {
        val temp = File(directory, ownerKey(userId) + "-" + UUID.randomUUID().toString())
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var size = 0L
                while (true) {
                    ensureActive(); val count = input.read(buffer); if (count < 0) break
                    size += count; if (size > config.maxImageBytes) throw GenerationException(GenerationFailure.INVALID_IMAGE)
                    output.write(buffer, 0, count)
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, options)
            val mime = options.outMimeType ?: throw GenerationException(GenerationFailure.INVALID_IMAGE)
            if (temp.length() == 0L || !mime.startsWith("image/") || options.outWidth <= 0 || options.outHeight <= 0)
                throw GenerationException(GenerationFailure.INVALID_IMAGE)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: throw GenerationException(GenerationFailure.INVALID_IMAGE)
            val target = File(directory, "${temp.name}.$extension")
            if (!temp.renameTo(target)) throw java.io.IOException()
            SourcePhoto(target.name, mime, options.outWidth, options.outHeight, target.length())
        } finally { temp.delete() }
    }
    suspend fun discard(photo: SourcePhoto) = withContext(Dispatchers.IO) { photoFile(photo).delete(); Unit }
}
