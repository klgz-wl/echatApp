package yumo.achat.app.data.backend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
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

class AchatRepository(
    context: Context,
    private val client: AchatBackendClient = AchatBackendClient(),
) : StorePaymentGateway {
    private val appContext = context.applicationContext
    private val sessionStore = AchatSessionStore(appContext)

    suspend fun loadHomeData(): AchatHomeData = withContext(Dispatchers.IO) {
        val session = ensureSession()

        val profile = runCatching { client.userProfile(session.token) }.getOrNull()
        val currency = runCatching { client.userCurrency(session.token) }.getOrNull()
        val videoTemplates = runCatching { client.templates(session.token, "video") }.getOrDefault(emptyList())
        val imageTemplates = runCatching { client.templates(session.token, "image") }.getOrDefault(emptyList())
        val videoCategories = runCatching { client.categories(session.token, "video") }.getOrDefault(emptyList())
        val imageCategories = runCatching { client.categories(session.token, "image") }.getOrDefault(emptyList())

        AchatHomeData(
            session = session,
            profile = profile,
            currency = currency,
            videoTemplates = videoTemplates,
            imageTemplates = imageTemplates,
            videoCategories = videoCategories,
            imageCategories = imageCategories,
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

    suspend fun createVisualGenerationTask(
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
    ): VisualGenerationTask = withContext(Dispatchers.IO) {
        require(templateId.isNotBlank()) { "Live template is required" }
        require(resourceId.isNotBlank()) { "Uploaded photo is required" }
        val session = ensureSession()
        client.createVisualGenerationTask(
            token = session.token,
            modality = modality,
            templateId = templateId,
            quality = quality,
            resourceId = resourceId,
        )
    }

    suspend fun getVisualGenerationTask(taskId: String): VisualGenerationTask = withContext(Dispatchers.IO) {
        require(taskId.isNotBlank()) { "Task id is required" }
        val session = ensureSession()
        client.visualGenerationTask(session.token, taskId)
    }

    suspend fun storeCatalog(): StoreCatalog = withContext(Dispatchers.IO) {
        val session = ensureSession()
        client.storeCatalog(session.token)
    }

    suspend fun userCurrencySnapshot(): UserCurrency = withContext(Dispatchers.IO) {
        val session = ensureSession()
        client.userCurrency(session.token)
    }

    override suspend fun createStoreOrder(productId: String): StoreOrder = withContext(Dispatchers.IO) {
        require(productId.isNotBlank()) { "Product id is required" }
        val session = ensureSession()
        client.createStoreOrder(session.token, productId)
    }

    override suspend fun initializeStorePayment(orderId: String): PaymentInitialization = withContext(Dispatchers.IO) {
        require(orderId.isNotBlank()) { "Order id is required" }
        val session = ensureSession()
        client.initializeStorePayment(session.token, orderId)
    }

    override suspend fun storePaymentStatus(orderId: String): PaymentOrderStatus = withContext(Dispatchers.IO) {
        val session = ensureSession()
        client.storePaymentStatus(session.token, orderId)
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
        val session = ensureSession()
        client.reportStorePaymentEvent(
            session.token, orderId, eventType, channelCode, openMode, url, errorCode, errorMessage,
        )
    }

    suspend fun generatedResources(page: Int = 1, pageSize: Int = 20): List<VisualResource> = withContext(Dispatchers.IO) {
        val session = ensureSession()
        client.visualResources(
            token = session.token,
            page = page,
            pageSize = pageSize,
            resourceType = "generated",
        )
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
