package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateFeedNavigationTest {
    @Test
    fun `next moves forward before the end`() {
        val result = moveTemplateFeedIndex(
            currentIndex = 1,
            totalItems = 3,
            direction = TemplateFeedDirection.Next,
        )

        assertEquals(2, result.index)
        assertFalse(result.reachedEdge)
    }

    @Test
    fun `next wraps to the beginning at the end`() {
        val result = moveTemplateFeedIndex(
            currentIndex = 3,
            totalItems = 3,
            direction = TemplateFeedDirection.Next,
        )

        assertEquals(1, result.index)
        assertFalse(result.reachedEdge)
    }

    @Test
    fun `previous wraps to the end at the beginning`() {
        val result = moveTemplateFeedIndex(
            currentIndex = 1,
            totalItems = 3,
            direction = TemplateFeedDirection.Previous,
        )

        assertEquals(3, result.index)
        assertFalse(result.reachedEdge)
    }

    @Test
    fun `virtual pager maps circular pages to real template positions`() {
        val initial = templatePagerInitialPage(totalItems = 3, currentIndex = 1)

        assertEquals(1, templateFeedIndexForPagerPage(initial, totalItems = 3))
        assertEquals(2, templateFeedIndexForPagerPage(initial + 1, totalItems = 3))
        assertEquals(3, templateFeedIndexForPagerPage(initial - 1, totalItems = 3))
        assertEquals(Int.MAX_VALUE, templatePagerPageCount(totalItems = 3))
        assertEquals(1, templatePagerPageCount(totalItems = 1))
        assertEquals(
            initial,
            nearestTemplatePagerPage(currentPage = 0, totalItems = 3, targetIndex = 1),
        )
    }

    @Test
    fun `precomposes nearby pages only when feed can switch`() {
        assertEquals(0, templatePagerPrecomposedPageCount(totalItems = 0))
        assertEquals(0, templatePagerPrecomposedPageCount(totalItems = 1))
        assertEquals(1, templatePagerPrecomposedPageCount(totalItems = 2))
        assertEquals(1, templatePagerPrecomposedPageCount(totalItems = 6))
    }
}
