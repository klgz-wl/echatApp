package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import yumo.achat.core.backend.WalletTransaction

class MeModulesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diamondHistoryShowsRealSignedRecordData() {
        compose.setContent {
            DiamondHistoryScreen(loadPage = {
                listOf(WalletTransaction("expense", "expense", 14, "Video generation", null,
                    "2026-04-09T17:55:00Z", null, null, null),
                    WalletTransaction("income", "income", 30, "Recharge", null,
                    "2026-04-09T18:00:00Z", null, null, null))
            }, onBack = {})
        }
        compose.onNodeWithText("Diamond History").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("-14.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("-14.00").assertIsDisplayed()
        compose.onNodeWithText("+30.00").assertIsDisplayed()
        compose.onNodeWithText("17:55 09/04/2026").assertIsDisplayed()
    }

    @Test fun diamondHistoryFailureCanRetryWithoutDisplayingFakeRecords() {
        var attempts = 0
        compose.setContent {
            DiamondHistoryScreen(loadPage = {
                if (attempts++ == 0) error("offline")
                emptyList()
            }, onBack = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unable to load records. Tap to retry.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unable to load records. Tap to retry.").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No diamond transactions yet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No diamond transactions yet").assertIsDisplayed()
    }
}
