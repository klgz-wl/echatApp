package yumo.achat.app.ui.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule val composeRule = createComposeRule()

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
