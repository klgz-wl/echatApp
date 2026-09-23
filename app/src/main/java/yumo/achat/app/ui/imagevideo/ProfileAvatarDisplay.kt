package yumo.achat.app.ui.imagevideo

internal fun profileAvatarModel(
    localAvatarUri: Any?,
    remoteAvatarUrl: String?,
    fallbackModel: Any,
): Any = localAvatarUri ?: remoteAvatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackModel
