package yumo.achat.app.ui.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun missingAgreementDisplaysE201AndBlocksDeviceAuthentication() {
        var attempts = 0
        composeRule.setContent {
            LoginScreen(false, null, onDeviceLogin = { attempts++ }, onAccountLogin = { _, _ -> })
        }
        composeRule.onNodeWithContentDescription("Agree to the Terms of Service and Privacy Policy").performClick()
        composeRule.onNodeWithText("Login with device").performClick()
        composeRule.onNodeWithText("Please agree to the Terms of Service and Privacy Policy first. (E-201)")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, attempts) }
    }

    @Test fun accountAndPasswordValidationKeepDistinctDiagnosticsAndBlockSubmission() {
        var attempts = 0
        composeRule.setContent {
            LoginScreen(false, null, onDeviceLogin = {}, onAccountLogin = { _, _ -> attempts++ })
        }
        composeRule.onNodeWithText("Sign in or create account").performClick()
        composeRule.onNodeWithText("SIGN IN →").performClick()
        composeRule.onNodeWithText("Account must have 3–50 characters without spaces. (E-202)").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("validuser")
        composeRule.onNodeWithText("SIGN IN →").performClick()
        composeRule.onNodeWithText("Password must have 6–18 characters without spaces. (E-203)").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, attempts) }
    }

    @Test fun choiceOpensAccountFormAndReturns() {
        composeRule.setContent {
            LoginScreen(
                submitting = false,
                serverError = null,
                onDeviceLogin = {},
                onAccountLogin = { _, _ -> },
            )
        }
        composeRule.onNodeWithText("Login with device").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in or create account").performClick()
        composeRule.onNodeWithText("Enter account").assertIsDisplayed()
        composeRule.onNodeWithText("Enter password").assertIsDisplayed()
        composeRule.onNodeWithText("SIGN IN →").assertIsDisplayed()
    }
}
