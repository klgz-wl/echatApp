package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun profileCardMatchesPrototypeStructureAndActions() {
        var editClicked = false
        var copyClicked = false
        composeRule.setContent {
            ProfileCard(
                profileName = "Quiet wanderer",
                profileId = "6248261746",
                avatarUrl = null,
                onEdit = { editClicked = true },
                onCopyId = { copyClicked = true },
            )
        }

        composeRule.onNodeWithContentDescription("Profile avatar").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet wanderer").assertIsDisplayed()
        composeRule.onNodeWithText("ID:6248261746").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy profile ID").performClick()
        composeRule.onNodeWithContentDescription("Edit profile").performClick()

        composeRule.runOnIdle {
            assertTrue(copyClicked)
            assertTrue(editClicked)
        }
    }
}
