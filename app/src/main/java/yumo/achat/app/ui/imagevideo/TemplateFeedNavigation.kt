package yumo.achat.app.ui.imagevideo

internal enum class TemplateFeedDirection {
    Previous,
    Next,
}

internal data class TemplateFeedNavigationResult(
    val index: Int,
    val reachedEdge: Boolean,
)

internal fun moveTemplateFeedIndex(
    currentIndex: Int,
    totalItems: Int,
    direction: TemplateFeedDirection,
): TemplateFeedNavigationResult {
    if (totalItems <= 0) {
        return TemplateFeedNavigationResult(index = currentIndex.coerceAtLeast(1), reachedEdge = true)
    }

    val normalizedIndex = currentIndex.coerceIn(1, totalItems)
    return when (direction) {
        TemplateFeedDirection.Previous -> {
            if (normalizedIndex == 1) {
                TemplateFeedNavigationResult(index = normalizedIndex, reachedEdge = true)
            } else {
                TemplateFeedNavigationResult(index = normalizedIndex - 1, reachedEdge = false)
            }
        }
        TemplateFeedDirection.Next -> {
            if (normalizedIndex == totalItems) {
                TemplateFeedNavigationResult(index = normalizedIndex, reachedEdge = true)
            } else {
                TemplateFeedNavigationResult(index = normalizedIndex + 1, reachedEdge = false)
            }
        }
    }
}
