package yumo.achat.app.data.backend

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

data class UserCurrency(
    val userId: String,
    val diamondBalance: Int,
    val diamondEarned: Int,
    val diamondSpent: Int,
)

data class VisualTemplate(
    val id: String,
    val categoryId: String?,
    val categoryName: String?,
    val name: String,
    val fileUrl: String,
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
    val videoCategories: List<VisualCategory>,
    val imageCategories: List<VisualCategory>,
)
