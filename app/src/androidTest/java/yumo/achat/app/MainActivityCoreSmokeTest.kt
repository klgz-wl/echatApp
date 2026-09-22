package yumo.achat.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityCoreSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionCompositionStartsWithDefaultCoreFactory() {
        composeRule.onNodeWithText("Image To Video").assertExists()
    }
}
