package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import yumo.achat.app.MainActivity

@RunWith(AndroidJUnit4::class)
class ImageToVideoScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun imageToVideoDesignIsDisplayed() {
        composeRule.onNodeWithText("Image To Video").assertIsDisplayed()
        composeRule.onNodeWithText("Hot").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play template").assertIsDisplayed()
        composeRule.onNodeWithText("1/109").assertIsDisplayed()
        composeRule.onNodeWithText("5s · 720P").assertIsDisplayed()
        composeRule.onNodeWithText("USE THIS TEMPLATE").assertIsDisplayed()
        composeRule.onNodeWithText("VIDEO").assertIsDisplayed()
        composeRule.onNodeWithText("IMAGE").assertIsDisplayed()
        composeRule.onNodeWithText("TOP UP").assertIsDisplayed()
        composeRule.onNodeWithText("ME").assertIsDisplayed()
    }

    @Test
    fun newTabBecomesSelectedWhenTapped() {
        composeRule.onNodeWithText("New").performClick()
        composeRule.onNodeWithText("New").assertIsSelected()
    }

    @Test
    fun nextTemplateUpdatesPageCounter() {
        composeRule.onNodeWithContentDescription("Next template").performClick()
        composeRule.onNodeWithText("2/109").assertIsDisplayed()
    }

    @Test
    fun playButtonTogglesToPause() {
        composeRule.onNodeWithContentDescription("Play template").performClick()
        composeRule.onNodeWithContentDescription("Pause template").assertIsDisplayed()
    }

    @Test
    fun imageNavigationBecomesSelectedWhenTapped() {
        composeRule.onNodeWithText("IMAGE").performClick()
        composeRule.onNodeWithText("IMAGE").assertIsSelected()
    }

    @Test
    fun imageNavigationShowsImageToImageDesign() {
        composeRule.onNodeWithText("IMAGE").performClick()

        composeRule.onNodeWithText("IMAGE TO IMAGE").assertIsDisplayed()
        composeRule.onNodeWithText("SINGLE IMAGE").assertIsDisplayed()
        composeRule.onNodeWithText("MULTI IMAGE").assertIsDisplayed()
        composeRule.onNodeWithText("USE THIS TEMPLATE").assertIsDisplayed()
    }

    @Test
    fun useTemplateOpensUploadPhotoScreen() {
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()

        composeRule.onNodeWithText("Upload photo").assertIsDisplayed()
        composeRule.onNodeWithText("TEMPLATE PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PHOTO").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to choose an image - we'll apply this template.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose photo").assertIsDisplayed()
        composeRule.onNodeWithText("CONTINUE").assertIsDisplayed()
    }

    @Test
    fun imageTemplateOpensUploadPhotoScreenWithImageNavigationSelected() {
        composeRule.onNodeWithText("IMAGE").performClick()
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()

        composeRule.onNodeWithText("Upload photo").assertIsDisplayed()
        composeRule.onNodeWithText("TEMPLATE PREVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("YOUR PHOTO").assertIsDisplayed()
        composeRule.onNodeWithText("IMAGE").assertIsSelected()
    }

    @Test
    fun backFromUploadPhotoReturnsToTemplateBrowser() {
        composeRule.onNodeWithText("USE THIS TEMPLATE").performClick()
        composeRule.onNodeWithContentDescription("Back to templates").performClick()

        composeRule.onNodeWithText("Image To Video").assertIsDisplayed()
        composeRule.onNodeWithText("Hot").assertIsDisplayed()
    }
}
