package yumo.achat.app.ui.imagevideo

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.platform.LocalDensity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.VisualCategory

class CatalogCategoryTabsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun backendLabelsAndIDsDriveSelection() {
        val categories = listOf(VisualCategory("cat-dance", "Dance", 1), VisualCategory("cat-portrait", "Portrait", 2), VisualCategory("cat-style", "Style", 3))
        var selected by mutableIntStateOf(0)
        var chosenId: String? = null
        compose.setContent { CategoryTabs(categories, selected) { selected = it; chosenId = categories[it].id } }
        compose.onNodeWithText("Dance").assertIsSelected()
        compose.onNodeWithText("Portrait").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("cat-portrait", chosenId) }
        compose.onAllNodesWithText("Hot").assertCountEquals(0)
        compose.onAllNodesWithText("New").assertCountEquals(0)
    }
    @Test fun gearBorderKeepsPrototypeSizeAndEightDpTitleGap() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            ZorvBrandHeader(0, false, {}, {}, {})
        }
        val gear = compose.onNodeWithTag("zorv-brand-gear").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Zorv").fetchSemanticsNode().boundsInRoot
        assertEquals(32f, gear.width / density, 1f)
        assertEquals(8f, (title.left - gear.right) / density, 1f)
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }
}
