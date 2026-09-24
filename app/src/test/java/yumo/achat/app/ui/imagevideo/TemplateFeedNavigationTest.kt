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
    fun `next stays and reports edge at the end`() {
        val result = moveTemplateFeedIndex(
            currentIndex = 3,
            totalItems = 3,
            direction = TemplateFeedDirection.Next,
        )

        assertEquals(3, result.index)
        assertTrue(result.reachedEdge)
    }

    @Test
    fun `previous stays and reports edge at the beginning`() {
        val result = moveTemplateFeedIndex(
            currentIndex = 1,
            totalItems = 3,
            direction = TemplateFeedDirection.Previous,
        )

        assertEquals(1, result.index)
        assertTrue(result.reachedEdge)
    }

    @Test
    fun `precomposes nearby pages only when feed can switch`() {
        assertEquals(0, templatePagerPrecomposedPageCount(totalItems = 0))
        assertEquals(0, templatePagerPrecomposedPageCount(totalItems = 1))
        assertEquals(1, templatePagerPrecomposedPageCount(totalItems = 2))
        assertEquals(1, templatePagerPrecomposedPageCount(totalItems = 6))
    }
}
