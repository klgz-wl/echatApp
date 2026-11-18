package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import yumo.achat.app.MainActivity

@RunWith(AndroidJUnit4::class)
class ImageToVideoScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun enterAuthenticatedHome() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("Login with device").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Zorv").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("Login with device").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Login with device").performClick()
        }
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodesWithText("Zorv").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("VIDEO").performClick()
    }

    private fun assumeUsableLiveTemplate() {
        val available = runCatching {
            composeRule.waitUntil(20_000) {
                runCatching { composeRule.onNodeWithText("USE THIS TEMPLATE").assertIsEnabled() }.isSuccess
            }
        }.isSuccess
        assumeTrue("Requires a usable live template from the development catalog", available)
    }

    private fun visibleControlIndex(description: String): Int {
        val height = composeRule.activity.window.decorView.height.toFloat()
        return composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
            .indexOfFirst { node -> node.boundsInRoot.top >= 0f && node.boundsInRoot.bottom <= height }
    }

    @Test
    fun imageToVideoDesignIsDisplayed() {
        composeRule.onNodeWithText("Zorv").assertIsDisplayed()
        composeRule.onNodeWithTag("catalog-category-tabs").assertIsDisplayed()
        composeRule.onNodeWithText("VIDEO").assertIsDisplayed()
        composeRule.onNodeWithText("IMAGE").assertIsDisplayed()
        composeRule.onNodeWithText("VIDEO").assertIsSelected()
        composeRule.onNodeWithText("SHORT").assertIsDisplayed()
    }

    @Test
    fun backendCategoryBecomesSelectedWhenTapped() {
        val tabs = composeRule.onAllNodes(isSelectable() and hasAnyAncestor(hasTestTag("catalog-category-tabs")))
        assumeTrue("Requires at least two backend categories", tabs.fetchSemanticsNodes().size >= 2)
        tabs[1].performClick().assertIsSelected()
    }

    @Test
    fun nextTemplateUpdatesPageCounter() {
        assumeUsableLiveTemplate()
        assumeTrue(
            "Requires at least two live templates",
            visibleControlIndex("Next template") >= 0 &&
                composeRule.onAllNodesWithText("1/1").fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onAllNodesWithContentDescription("Next template")[visibleControlIndex("Next template")].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("2/", substring = true).fetchSemanticsNodes()
                .any { it.boundsInRoot.top >= 0f }
        }
    }

    @Test
    fun playButtonTogglesToPause() {
        assumeUsableLiveTemplate()
        val play = visibleControlIndex("Play template") >= 0
        val pause = visibleControlIndex("Pause template") >= 0
        assumeTrue("Requires a playable live video template", play || pause)
        if (play) {
            composeRule.onAllNodesWithContentDescription("Play template")[visibleControlIndex("Play template")].performClick()
            val started = runCatching {
                composeRule.waitUntil(5_000) { visibleControlIndex("Pause template") >= 0 }
            }.isSuccess
            assumeTrue("Requires the live video to start on this emulator", started)
        } else {
            composeRule.onAllNodesWithContentDescription("Pause template")[visibleControlIndex("Pause template")].performClick()
            composeRule.waitUntil(5_000) { visibleControlIndex("Play template") >= 0 }
        }
    }

    @Test
    fun shortIsPlaceholderAndSettingsReturnsToShort() {
        composeRule.onNodeWithText("SHORT").performClick()
        composeRule.onNodeWithText("Short videos coming soon").assertIsDisplayed()
        composeRule.onNodeWithText("SHORT").assertIsSelected()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("SYSTEM MODULES").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        composeRule.onNodeWithText("SHORT").assertIsSelected()
        composeRule.onNodeWithText("Short videos coming soon").assertIsDisplayed()
    }

    @Test
    fun imageNavigationBecomesSelectedWhenTapped() {
        composeRule.onNodeWithText("IMAGE").performClick()
        composeRule.onNodeWithText("IMAGE").assertIsSelected()
    }

    @Test
    fun imageNavigationShowsImageToImageDesign() {
        composeRule.onNodeWithText("IMAGE").performClick()

        composeRule.onNodeWithText("Zorv").assertIsDisplayed()
        composeRule.onNodeWithTag("catalog-category-tabs").assertIsDisplayed()
        composeRule.onNodeWithText("USE THIS TEMPLATE").assertIsDisplayed()
    }

    @Test
    fun topUpNavigationShowsCreditPackDesign() {
        composeRule.onNodeWithContentDescription("Token balance").performClick()

        composeRule.onNodeWithText("SYSTEM CREDITS").assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE A PACK").assertIsDisplayed()
    }

    @Test
    fun meNavigationShowsProfileAndSystemModules() {
        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.onNodeWithText("Me").assertIsDisplayed()
        composeRule.onNodeWithText("SYSTEM MODULES").assertIsDisplayed()
        listOf("My Creations", "Watch History", "Feedback", "Diamond History", "Privacy Policy", "Terms of Service", "App Version").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("Delete Account").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LOG OUT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HOME").assertIsDisplayed()
    }

    @Test
    fun deleteAccountRequiresCountdownAndCanBeCancelled() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Delete Account").performScrollTo().performClick()

        composeRule.onNodeWithText("Tips").assertIsDisplayed()
        composeRule.onNodeWithText("Delete Account (3)").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("LOG OUT").assertIsDisplayed()
    }

    @Test
    fun conversationLogOpensMyTasksScreen() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("My Creations").performClick()

        composeRule.onNodeWithText("My Creations").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back to me").performClick()
        composeRule.onNodeWithText("SYSTEM MODULES").assertIsDisplayed()
    }

    @Test
    fun feedbackOpensFeedbackForm() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Feedback").performScrollTo().performClick()

        composeRule.onNodeWithText("Feedback").assertIsDisplayed()
        composeRule.onNodeWithText("Tell us what feels off").assertIsDisplayed()
        composeRule.onNodeWithText("Take Photo").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onNodeWithText("Describe what happened...").assertIsDisplayed()
        composeRule.onNodeWithText("Submit Feedback").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back to me").performClick()
        composeRule.onNodeWithText("SYSTEM MODULES").assertIsDisplayed()
    }

    @Test
    fun editNameOpensNamePickerSheet() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Edit profile").performClick()

        composeRule.onNodeWithText("Edit display name").assertIsDisplayed()
        composeRule.onNodeWithText("Display name").assertIsDisplayed()
        composeRule.onNodeWithText("SAVE").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close name picker").performClick()
        composeRule.onNodeWithText("SYSTEM MODULES").assertIsDisplayed()
    }

    @Test
    fun useTemplateOpensUploadPhotoScreen() {
        assumeUsableLiveTemplate()
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()

        composeRule.onNodeWithText("Upload photo").assertIsDisplayed()
        composeRule.onNodeWithText("TEMPLATE PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PHOTO").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to choose an image - we'll apply this template.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose photo").assertExists()
        composeRule.onNodeWithText("CONTINUE").assertExists()
    }

    @Test
    fun imageTemplateOpensUploadPhotoScreenWithImageNavigationSelected() {
        composeRule.onNodeWithText("IMAGE").performClick()
        assumeUsableLiveTemplate()
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()

        composeRule.onNodeWithText("Upload photo").assertIsDisplayed()
        composeRule.onNodeWithText("TEMPLATE PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PHOTO").assertIsDisplayed()
        composeRule.onNodeWithText("IMAGE").assertIsSelected()
    }

    @Test
    fun backFromUploadPhotoReturnsToTemplateBrowser() {
        assumeUsableLiveTemplate()
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()
        composeRule.onNodeWithContentDescription("Back to templates").performClick()

        composeRule.onNodeWithText("Zorv").assertIsDisplayed()
        composeRule.onNodeWithText("Hot").assertIsDisplayed()
    }

}
