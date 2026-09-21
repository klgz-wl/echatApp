package yumo.achat.app.data.backend

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException

data class AuthSession(
    val userId: String,
    val token: String,
    val refreshToken: String,
    val sessionId: String,
    val isAnonymous: Boolean,
)

data class UserProfile(
    val id: String,
    val username: String?,
    val nickname: String?,
    val name: String?,
    val avatarUrl: String?,
    val largeAvatarUrl: String?,
) {
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: username?.takeIf { it.isNotBlank() }
            ?: "Quiet wanderer"
}

data class UploadedFile(
    val id: String,
    val fileUrl: String,
    val storagePath: String,
    val mimeType: String,
)

data class UserCurrency(
    val userId: String,
    val diamondBalance: Int,
    val diamondEarned: Int,
    val diamondSpent: Int,
)

data class StorePaymentProvider(
    val priority: Int,
    val code: String,
    val name: String,
    val supportedPlatforms: List<String>,
)

data class StoreProduct(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val value: Int,
    val bonusValue: Int,
    val firstBuyBonusValue: Int,
    val currency: String,
    val originalPrice: BigDecimal,
    val price: BigDecimal,
    val firstBuyPrice: BigDecimal,
    val discountRate: BigDecimal,
    val firstBuyDiscount: BigDecimal,
    val icon: String,
    val isFirstBuyPromotion: Boolean,
    val isPromotion: Boolean,
    val isSubscription: Boolean,
    val promotionType: String,
    val sortOrder: Int,
    val tags: String,
    val thirdPartyProductId: String,
    val vipLevel: Int,
)

data class StoreUserInfo(
    val currentDiamond: Int,
    val hasMadeFirstPurchase: Boolean,
    val isVip: Boolean,
)

data class StoreCatalog(
    val products: List<StoreProduct>,
    val paymentProviders: List<StorePaymentProvider>,
    val userInfo: StoreUserInfo?,
)

data class StoreOrder(
    val id: String,
    val number: String,
    val productId: String,
    val productName: String,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val createdAt: String,
    val paymentUrl: String,
    val obfuscatedAccountId: String,
    val obfuscatedProfileId: String,
)

data class PaymentInitialization(
    val orderId: String,
    val channelType: String,
    val channelCode: String,
    val openMode: String,
    val paymentUrl: String,
    val expiresAt: String?,
    val queryIntervalSeconds: Int,
    val maxQuerySeconds: Int,
    val sdkProductId: String,
)

data class PreparedStorePayment(
    val order: StoreOrder,
    val initialization: PaymentInitialization,
)

data class ThirdPartyPaymentStatus(
    val channelCode: String,
    val status: String,
    val openMode: String,
    val expiresAt: String?,
)

data class PaymentOrderStatus(
    val orderId: String,
    val status: String,
    val paymentMethod: String,
    val fulfillmentStatus: String,
    val paidAt: String?,
    val verifiedAt: String?,
    val fulfilledAt: String?,
    val thirdPartyPayment: ThirdPartyPaymentStatus?,
)

data class VisualTemplate(
    val id: String,
    val categoryId: String?,
    val categoryName: String?,
    val name: String,
    val fileUrl: String,
    val previewUrl: String = "",
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationSeconds: Int,
    val hotScore: Int,
    val fastPrice: Int?,
    val qualityPrice: Int?,
) {
    val displayPrice: Int?
        get() = fastPrice ?: qualityPrice
}

data class VisualCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

data class TemplateLoadResult(
    val templates: List<VisualTemplate>,
    val errorMessage: String?,
)

internal suspend inline fun loadTemplateResult(
    fallbackMessage: String,
    crossinline load: suspend () -> List<VisualTemplate>,
): TemplateLoadResult = try {
    TemplateLoadResult(templates = load(), errorMessage = null)
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    TemplateLoadResult(
        templates = emptyList(),
        errorMessage = error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage,
    )
}

data class VisualResource(
    val id: String,
    val taskId: String?,
    val resourceType: String,
    val modality: String,
    val templateId: String? = null,
    val templateName: String? = null,
    val url: String,
    val thumbnailUrl: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationSeconds: Int,
    val createdAt: String? = null,
    val expiresAt: String? = null,
)

data class VisualGenerationTask(
    val taskId: String,
    val status: String,
    val modality: String,
    val quality: String,
    val templateId: String,
    val diamondCost: Int,
    val estimatedPollIntervalSeconds: Int?,
    val refunded: Boolean,
    val errorMessage: String?,
    val resource: VisualResource?,
)

data class AchatHomeData(
    val session: AuthSession,
    val profile: UserProfile?,
    val currency: UserCurrency?,
    val videoTemplates: List<VisualTemplate>,
    val imageTemplates: List<VisualTemplate>,
    val videoTemplateErrorMessage: String?,
    val imageTemplateErrorMessage: String?,
    val videoCategories: List<VisualCategory>,
    val imageCategories: List<VisualCategory>,
)
