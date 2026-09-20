package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NameEditorSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nameEditorAllowsSavingAChangedName() {
        var savedName = ""
        composeRule.setContent {
            NameEditorSheet(
                currentName = "Quiet wanderer",
                isSaving = false,
                errorMessage = null,
                onClose = {},
                onSave = { savedName = it },
            )
        }

        composeRule.onNodeWithText("Quiet wanderer").assertIsFocused()
        composeRule.onNodeWithText("Quiet wanderer").performTextReplacement("Nova Quinn")
        composeRule.onNodeWithText("SAVE").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("Nova Quinn", savedName) }
    }

    @Test
    fun nameEditorContentStaysAboveImeInset() {
        composeRule.setContent {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                NameEditorSheet(
                    currentName = "Quiet wanderer",
                    isSaving = false,
                    errorMessage = null,
                    onClose = {},
                    onSave = {},
                    imeInsets = WindowInsets(bottom = 300.dp),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        val inputBottom = composeRule.onNodeWithText("Quiet wanderer")
            .fetchSemanticsNode().boundsInRoot.bottom
        val maximumBottom = with(composeRule.density) { 550.dp.toPx() }
        assertTrue("Input bottom $inputBottom should stay above $maximumBottom", inputBottom <= maximumBottom)
    }
}
