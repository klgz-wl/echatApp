package yumo.achat.app.ui.imagevideo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import yumo.achat.app.ui.theme.AchatTheme
import yumo.achat.core.backend.AchatAppGateway
import yumo.achat.core.backend.AchatHomeData
import yumo.achat.core.backend.AuthSession
import yumo.achat.core.backend.StoreCatalog
import yumo.achat.core.backend.StoreOrder
import yumo.achat.core.backend.StorePaymentGateway
import yumo.achat.core.backend.TemplateLoadResult
import yumo.achat.core.backend.PaymentInitialization
import yumo.achat.core.backend.PaymentOrderStatus
import yumo.achat.core.backend.ProfileEditingGateway
import yumo.achat.core.backend.UserProfile
import yumo.achat.core.backend.UserCurrency
import yumo.achat.core.backend.VisualCategory
import yumo.achat.core.backend.VisualGenerationTask
import yumo.achat.core.backend.VisualResource
import yumo.achat.core.backend.VisualTemplate

class ImageToVideoTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gateway = DeterministicAchatGateway()
        val topUpController = TopUpPaymentController(gateway, lifecycleScope) { it.message ?: "Test payment error" }
        val profileController = ProfileEditingController(gateway, lifecycleScope) { it.message ?: "Test profile error" }
        setContent {
            AchatTheme {
                ImageToVideoScreen(
                    repositoryFactory = { gateway },
                    topUpControllerOverride = topUpController,
                    profileControllerOverride = profileController,
                    launchOfficialOverride = { _, _ -> },
                )
            }
        }
    }
}

private class DeterministicAchatGateway : AchatAppGateway, StorePaymentGateway, ProfileEditingGateway {
    private val videoTemplates = listOf(template("video-1", "First template"), template("video-2", "Second template"))
    private val imageTemplates = listOf(template("image-1", "Image template", modality = "image"))

    override suspend fun loadHomeData(): AchatHomeData = AchatHomeData(
        session = AuthSession("test-user", "token", "refresh", "session", true),
        profile = null,
        currency = UserCurrency("test-user", 120, 0, 0),
        videoTemplates = videoTemplates,
        imageTemplates = imageTemplates,
        videoTemplateErrorMessage = null,
        imageTemplateErrorMessage = null,
        videoCategories = emptyList<VisualCategory>(),
        imageCategories = emptyList<VisualCategory>(),
    )

    override suspend fun loadTemplates(modality: String): TemplateLoadResult = TemplateLoadResult(
        templates = if (modality == "video") videoTemplates else imageTemplates,
        errorMessage = null,
    )

    override suspend fun storeCatalog(): StoreCatalog = StoreCatalog(emptyList(), emptyList(), null)

    override suspend fun userCurrencySnapshot(): UserCurrency = UserCurrency("test-user", 120, 0, 0)

    override suspend fun generatedResources(page: Int, pageSize: Int): List<VisualResource> = emptyList()

    override suspend fun uploadSourceImage(uri: Uri): VisualResource = error("Not used by navigation tests")

    override suspend fun createVisualGenerationTask(
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
    ): VisualGenerationTask = error("Not used by navigation tests")

    override suspend fun getVisualGenerationTask(taskId: String): VisualGenerationTask = error("No active tasks")

    override suspend fun createStoreOrder(productId: String): StoreOrder = error("Not used by navigation tests")

    override suspend fun initializeStorePayment(orderId: String): PaymentInitialization = error("Not used by navigation tests")

    override suspend fun storePaymentStatus(orderId: String): PaymentOrderStatus = error("Not used by navigation tests")

    override suspend fun reportStorePaymentEvent(
        orderId: String,
        eventType: String,
        channelCode: String,
        openMode: String,
        url: String,
        errorCode: String,
        errorMessage: String,
    ) = Unit

    override suspend fun updateProfileName(name: String): UserProfile = error("Not used by navigation tests")

    override suspend fun updateProfileAvatar(uri: Uri): UserProfile = error("Not used by navigation tests")

    private fun template(id: String, name: String, modality: String = "video") = VisualTemplate(
        id = id,
        categoryId = null,
        categoryName = null,
        name = name,
        fileUrl = "",
        mimeType = "image/jpeg",
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 1,
        fastPrice = 1,
        qualityPrice = 2,
    )
}
