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
        TemplateFeedDirection.Previous -> TemplateFeedNavigationResult(
            index = if (normalizedIndex == 1) totalItems else normalizedIndex - 1,
            reachedEdge = false,
        )
        TemplateFeedDirection.Next -> TemplateFeedNavigationResult(
            index = if (normalizedIndex == totalItems) 1 else normalizedIndex + 1,
            reachedEdge = false,
        )
    }
}

internal fun templatePagerPageCount(totalItems: Int): Int = when {
    totalItems <= 0 -> 0
    totalItems == 1 -> 1
    else -> Int.MAX_VALUE
}

internal fun templateFeedIndexForPagerPage(page: Int, totalItems: Int): Int {
    if (totalItems <= 0) return 1
    return Math.floorMod(page, totalItems) + 1
}

internal fun templatePagerInitialPage(totalItems: Int, currentIndex: Int): Int {
    if (totalItems <= 1) return 0
    val targetOffset = (currentIndex.coerceIn(1, totalItems) - 1)
    val middle = Int.MAX_VALUE / 2
    return middle - Math.floorMod(middle, totalItems) + targetOffset
}

internal fun nearestTemplatePagerPage(
    currentPage: Int,
    totalItems: Int,
    targetIndex: Int,
): Int {
    if (totalItems <= 1) return 0
    if (currentPage < totalItems || currentPage >= Int.MAX_VALUE - totalItems) {
        return templatePagerInitialPage(totalItems, targetIndex)
    }
    val targetOffset = targetIndex.coerceIn(1, totalItems) - 1
    val cycleStart = currentPage - Math.floorMod(currentPage, totalItems)
    return listOf(cycleStart + targetOffset - totalItems, cycleStart + targetOffset, cycleStart + targetOffset + totalItems)
        .filter { it in 0 until Int.MAX_VALUE }
        .minByOrNull { kotlin.math.abs(it.toLong() - currentPage.toLong()) }
        ?: templatePagerInitialPage(totalItems, targetIndex)
}

internal fun templatePagerPrecomposedPageCount(totalItems: Int): Int =
    if (totalItems > 1) 1 else 0
