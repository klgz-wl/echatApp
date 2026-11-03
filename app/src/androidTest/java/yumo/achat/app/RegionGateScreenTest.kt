package yumo.achat.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionGateScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun restrictedBodyUsesActualSignalCodeWithoutReferenceLine() {
        val state = mutableStateOf(RegionGateState(restricted = true, diagnosticCode = "R101"))
        composeRule.setContent { RegionGateScreen(state.value, onRetry = {}, onExit = {}) }
        listOf("R101", "R102", "R103").forEach { code ->
            composeRule.runOnIdle { state.value = RegionGateState(restricted = true, diagnosticCode = code) }
            composeRule.onNodeWithText("This app is not available in your region. ($code)").assertIsDisplayed()
            composeRule.onNodeWithText("Ref:", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("Retry").assertDoesNotExist()
        }
    }

    @Test fun unavailableBodyUsesActualHttpStatusAndKeepsRetryAndExit() {
        var retries = 0
        var exits = 0
        composeRule.setContent {
            RegionGateScreen(
                RegionGateState(error = true, diagnosticCode = "R205-429"),
                onRetry = { retries++ }, onExit = { exits++ },
            )
        }
        composeRule.onNodeWithText("We couldn’t verify availability. Please try again. (R205-429)").assertIsDisplayed()
        composeRule.onNodeWithText("Ref:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Exit").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, exits)
        }
    }

    @Test fun offlineBodyUsesR200WithoutAuthenticationCode() {
        composeRule.setContent {
            RegionGateScreen(RegionGateState(error = true, offline = true, diagnosticCode = "R200"), {}, {})
        }
        composeRule.onNodeWithText("An internet connection is required. Please connect and try again. (R200)").assertIsDisplayed()
        composeRule.onNodeWithText("E-206", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Ref:", substring = true).assertDoesNotExist()
    }

    @Test fun checkingDoesNotExposeStaleDiagnostic() {
        composeRule.setContent {
            RegionGateScreen(RegionGateState(busy = true, diagnosticCode = "R201"), {}, {})
        }
        composeRule.onNodeWithText("Checking availability…").assertIsDisplayed()
        composeRule.onNodeWithText("R201", substring = true).assertDoesNotExist()
    }

    @Test fun longOfflineBodyWrapsAtLargeFontWithoutOverlappingActions() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                RegionGateScreen(RegionGateState(error = true, offline = true, diagnosticCode = "R200"), {}, {})
            }
        }
        val message = composeRule.onNodeWithText("An internet connection is required. Please connect and try again. (R200)")
        message.assertIsDisplayed()
        val retry = composeRule.onNodeWithText("Retry")
        retry.assertIsDisplayed()
        composeRule.onNodeWithText("Exit").assertIsDisplayed()
        assertTrue(message.fetchSemanticsNode().boundsInRoot.bottom <= retry.fetchSemanticsNode().boundsInRoot.top)
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "error-region-large-font.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
