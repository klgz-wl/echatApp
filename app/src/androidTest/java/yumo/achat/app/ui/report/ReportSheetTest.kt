package yumo.achat.app.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun previewControlOpensReportSheetAndSubmitsOnce() {
        composeRule.setContent {
            var open by remember { mutableStateOf(false) }
            var success by remember { mutableStateOf(false) }
            Box {
                MediaReportIconButton(onClick = { open = true })
                if (open) MediaReportSheet(
                    mediaId = "template-1",
                    onDismiss = { open = false },
                    onSuccess = { success = true; open = false },
                    repository = MediaReportRepository { },
                )
                if (success) Text("Submitted")
            }
        }

        composeRule.onNodeWithContentDescription("Report content").performClick()
        composeRule.onNodeWithText("Feel free to report this content if you think it’s inappropriate.").assertIsDisplayed()
        val viewportBounds = composeRule.onAllNodes(isRoot())[1].getUnclippedBoundsInRoot()
        val viewportHeight = viewportBounds.bottom - viewportBounds.top
        val copyTop = composeRule.onNodeWithText("Feel free to report this content if you think it’s inappropriate.")
            .getUnclippedBoundsInRoot().top
        assertTrue("Report sheet must stay in the lower half", copyTop > viewportHeight * .35f)
        composeRule.onNodeWithText("REPORT").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Submitted").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
