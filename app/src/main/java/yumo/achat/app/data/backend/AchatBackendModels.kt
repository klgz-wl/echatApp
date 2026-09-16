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

data class AchatHomeData(
    val session: AuthSession,
    val profile: UserProfile?,
    val currency: UserCurrency?,
    val videoTemplates: List<VisualTemplate>,
    val imageTemplates: List<VisualTemplate>,
)
