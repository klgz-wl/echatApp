package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
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

        composeRule.onNodeWithText("Quiet wanderer").performTextReplacement("Nova Quinn")
        composeRule.onNodeWithText("SAVE").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("Nova Quinn", savedName) }
    }
}
