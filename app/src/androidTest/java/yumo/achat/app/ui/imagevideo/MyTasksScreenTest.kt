package yumo.achat.app.ui.imagevideo

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import yumo.achat.app.ui.theme.AchatTheme

class MyTasksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun successfulEmptyStateMatchesFigmaDiagnosticsCard() {
        composeRule.setContent {
            AchatTheme {
                MyTasksScreen(
                    tasks = emptyList(),
                    isLoading = false,
                    errorMessage = null,
                    onBack = {},
                    onOpenTask = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("NO TASKA YES").assertIsDisplayed()
        composeRule.onNodeWithText("/// NULL_STREAM").assertIsDisplayed()
    }

    @Test
    fun queryFailureDoesNotPretendThereAreNoTasks() {
        composeRule.setContent {
            AchatTheme {
                MyTasksScreen(
                    tasks = emptyList(),
                    isLoading = false,
                    errorMessage = "Failed to load task history",
                    onBack = {},
                    onOpenTask = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Failed to load task history").assertIsDisplayed()
        composeRule.onAllNodesWithText("NO TASKA YES").assertCountEquals(0)
    }

    @Test
    fun loadingAndNonEmptyStatesDoNotShowTheEmptyCard() {
        val task = TrackedGenerationTask(
            taskId = "task-12345678",
            title = "Newest task",
            modality = "image",
            status = "processing",
            resultUrl = null,
            mimeType = "",
            errorMessage = null,
        )
        composeRule.setContent {
            AchatTheme {
                MyTasksScreen(
                    tasks = listOf(task),
                    isLoading = true,
                    errorMessage = null,
                    onBack = {},
                    onOpenTask = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Loading creations...").assertIsDisplayed()
        composeRule.onNodeWithText("Newest task").assertIsDisplayed()
        composeRule.onAllNodesWithText("NO TASKA YES").assertCountEquals(0)
    }

    @Test
    fun generationResultScreenShowsOpenableVideoPreview() {
        val task = TrackedGenerationTask(
            taskId = "task-video-1",
            title = "Video result",
            modality = "video",
            status = "succeeded",
            resultUrl = "https://test.appjoly.com/generated/video.mp4",
            mimeType = "video/mp4",
            errorMessage = null,
            thumbnailUrl = "https://test.appjoly.com/generated/poster.jpg",
        )

        composeRule.setContent {
            AchatTheme {
                GenerationResultScreen(
                    task = task,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Video result").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Selected template preview").assertIsDisplayed()
    }
}
