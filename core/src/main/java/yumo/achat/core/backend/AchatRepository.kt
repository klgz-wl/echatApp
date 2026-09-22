package yumo.achat.core.backend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface StorePaymentGateway {
    suspend fun createStoreOrder(productId: String): StoreOrder
    suspend fun initializeStorePayment(orderId: String): PaymentInitialization
    suspend fun storePaymentStatus(orderId: String): PaymentOrderStatus
    suspend fun reportStorePaymentEvent(
        orderId: String,
        eventType: String,
        channelCode: String,
        openMode: String,
        url: String = "",
        errorCode: String = "",
        errorMessage: String = "",
    )
}

interface ProfileEditingGateway {
    suspend fun updateProfileName(name: String): UserProfile
    suspend fun updateProfileAvatar(uri: Uri): UserProfile
}

interface AchatAppGateway {
    suspend fun loadHomeData(): AchatHomeData
    suspend fun loadTemplates(modality: String): TemplateLoadResult
    suspend fun uploadSourceImage(uri: Uri): VisualResource
    suspend fun createVisualGenerationTask(
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
    ): VisualGenerationTask
    suspend fun getVisualGenerationTask(taskId: String): VisualGenerationTask
    suspend fun storeCatalog(): StoreCatalog
    suspend fun userCurrencySnapshot(): UserCurrency
    suspend fun generatedResources(page: Int = 1, pageSize: Int = 20): List<VisualResource>
}

class AchatRepository(
    context: Context,
    configuration: AchatBackendConfiguration,
) : AchatAppGateway, StorePaymentGateway, ProfileEditingGateway {
    private val appContext = context.applicationContext
    private val client = AchatBackendClient(configuration)
    private val sessionManager = AchatSessionManager.application(context, configuration)

    override suspend fun loadHomeData(): AchatHomeData = withContext(Dispatchers.IO) {
        sessionManager.session()
        coroutineScope {
            val profile = async {
                optionalBackendValue { sessionManager.authenticated(client::userProfile) }
            }
            val currency = async {
                optionalBackendValue { sessionManager.authenticated(client::userCurrency) }
            }
            val videoTemplates = async {
                loadTemplateResult("Unable to load video templates") {
                    sessionManager.authenticated { token -> client.templates(token, "video") }
                }
            }
            val imageTemplates = async {
                loadTemplateResult("Unable to load image templates") {
                    sessionManager.authenticated { token -> client.templates(token, "image") }
                }
            }
            val videoCategories = async {
                optionalBackendValue {
                    sessionManager.authenticated { token -> client.categories(token, "video") }
                }.orEmpty()
            }
            val imageCategories = async {
                optionalBackendValue {
                    sessionManager.authenticated { token -> client.categories(token, "image") }
                }.orEmpty()
            }
            val loadedVideoTemplates = videoTemplates.await()
            val loadedImageTemplates = imageTemplates.await()

            AchatHomeData(
                session = sessionManager.session(),
                profile = profile.await(),
                currency = currency.await(),
                videoTemplates = loadedVideoTemplates.templates,
                imageTemplates = loadedImageTemplates.templates,
                videoTemplateErrorMessage = loadedVideoTemplates.errorMessage,
                imageTemplateErrorMessage = loadedImageTemplates.errorMessage,
                videoCategories = videoCategories.await(),
                imageCategories = imageCategories.await(),
            )
        }
    }

    override suspend fun loadTemplates(modality: String): TemplateLoadResult = withContext(Dispatchers.IO) {
        require(modality == "video" || modality == "image") { "Unsupported template modality" }
        loadTemplateResult("Unable to load $modality templates") {
            sessionManager.authenticated { token -> client.templates(token, modality) }
        }
    }

    override suspend fun uploadSourceImage(uri: Uri): VisualResource = withContext(Dispatchers.IO) {
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
        sessionManager.authenticated { token -> client.uploadVisualResource(token, part) }
    }

    override suspend fun updateProfileName(name: String): UserProfile = withContext(Dispatchers.IO) {
        sessionManager.authenticated { token ->
            client.updateUserProfile(
                token = token,
                nickname = validatedProfileNickname(name),
            )
        }
    }

    override suspend fun updateProfileAvatar(uri: Uri): UserProfile = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val contentType = validatedProfileAvatarContentType(resolver.getType(uri))
        val fileName = resolver.displayName(uri) ?: defaultFileName(contentType)
        val bytes = resolver.openInputStream(uri)?.use(::readProfileAvatarBytes)
            ?: error("Unable to read selected image")
        val upload = sessionManager.authenticated { token ->
            client.uploadProfileAvatar(
                token = token,
                parts = profileAvatarUploadParts(fileName, contentType, bytes),
            )
        }
        sessionManager.authenticated { token ->
            client.updateUserProfile(token = token, avatarUrl = upload.fileUrl)
        }
    }

    override suspend fun createVisualGenerationTask(
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
    ): VisualGenerationTask = withContext(Dispatchers.IO) {
        require(templateId.isNotBlank()) { "Live template is required" }
        require(resourceId.isNotBlank()) { "Uploaded photo is required" }
        sessionManager.authenticated { token ->
            client.createVisualGenerationTask(
                token = token,
                modality = modality,
                templateId = templateId,
                quality = quality,
                resourceId = resourceId,
            )
        }
    }

    override suspend fun getVisualGenerationTask(taskId: String): VisualGenerationTask = withContext(Dispatchers.IO) {
        require(taskId.isNotBlank()) { "Task id is required" }
        sessionManager.authenticated { token -> client.visualGenerationTask(token, taskId) }
    }

    override suspend fun storeCatalog(): StoreCatalog = withContext(Dispatchers.IO) {
        sessionManager.authenticated(client::storeCatalog)
    }

    override suspend fun userCurrencySnapshot(): UserCurrency = withContext(Dispatchers.IO) {
        sessionManager.authenticated(client::userCurrency)
    }

    override suspend fun createStoreOrder(productId: String): StoreOrder = withContext(Dispatchers.IO) {
        require(productId.isNotBlank()) { "Product id is required" }
        sessionManager.authenticated { token -> client.createStoreOrder(token, productId) }
    }

    override suspend fun initializeStorePayment(orderId: String): PaymentInitialization = withContext(Dispatchers.IO) {
        require(orderId.isNotBlank()) { "Order id is required" }
        sessionManager.authenticated { token -> client.initializeStorePayment(token, orderId) }
    }

    override suspend fun storePaymentStatus(orderId: String): PaymentOrderStatus = withContext(Dispatchers.IO) {
        sessionManager.authenticated { token -> client.storePaymentStatus(token, orderId) }
    }

    override suspend fun reportStorePaymentEvent(
        orderId: String,
        eventType: String,
        channelCode: String,
        openMode: String,
        url: String,
        errorCode: String,
        errorMessage: String,
    ) = withContext(Dispatchers.IO) {
        sessionManager.authenticated { token ->
            client.reportStorePaymentEvent(
                token, orderId, eventType, channelCode, openMode, url, errorCode, errorMessage,
            )
        }
    }

    override suspend fun generatedResources(page: Int, pageSize: Int): List<VisualResource> = withContext(Dispatchers.IO) {
        sessionManager.authenticated { token ->
            client.visualResources(
                token = token,
                page = page,
                pageSize = pageSize,
                resourceType = "generated",
            )
        }
    }

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

private suspend inline fun <T> optionalBackendValue(crossinline load: suspend () -> T): T? = try {
    load()
} catch (error: CancellationException) {
    throw error
} catch (_: Throwable) {
    null
}
