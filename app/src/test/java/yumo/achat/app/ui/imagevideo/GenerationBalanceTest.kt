package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import yumo.achat.app.data.backend.VisualGenerationTask

class GenerationBalanceTest {
    @Test
    fun `task creation immediately deducts backend reported diamond cost`() {
        assertEquals(91, balanceAfterGenerationTaskCreated(currentBalance = 100, diamondCost = 9))
        assertEquals(0, balanceAfterGenerationTaskCreated(currentBalance = 5, diamondCost = 9))
    }

    @Test
    fun `terminal task status requests authoritative currency refresh`() {
        assertFalse(shouldRefreshBalanceForTaskStatus("pending"))
        assertTrue(shouldRefreshBalanceForTaskStatus("succeeded"))
        assertTrue(shouldRefreshBalanceForTaskStatus("failed"))
    }

    @Test
    fun `same task id is charged optimistically only once`() {
        val charged = setOf("task-1")
        assertFalse(shouldApplyGenerationCharge("task-1", charged))
        assertTrue(shouldApplyGenerationCharge("task-2", charged))
    }

    @Test
    fun `older currency refresh response cannot overwrite latest request`() {
        assertFalse(shouldApplyCurrencySnapshot(responseSerial = 1, latestSerial = 2))
        assertTrue(shouldApplyCurrencySnapshot(responseSerial = 2, latestSerial = 2))
    }

    @Test
    fun `polling continues through unchanged status and transient failure`() = runBlocking {
        val responses = ArrayDeque<Result<VisualGenerationTask>>().apply {
            add(Result.success(task("processing")))
            add(Result.failure(IllegalStateException("temporary")))
            add(Result.success(task("succeeded")))
        }
        val statuses = mutableListOf<String>()

        val terminal = pollGenerationTaskUntilFinished(
            initialTask = task("processing").toTrackedGenerationTask("Template"),
            waitForNextPoll = {},
            fetch = { responses.removeFirst().getOrThrow() },
            onUpdate = { statuses += it.status },
        )

        assertEquals("succeeded", terminal.status)
        assertEquals(listOf("processing", "succeeded"), statuses)
    }

    private fun task(status: String) = VisualGenerationTask(
        taskId = "task-1",
        status = status,
        modality = "image",
        quality = "fast",
        templateId = "template-1",
        diamondCost = 9,
        estimatedPollIntervalSeconds = 1,
        refunded = false,
        errorMessage = null,
        resource = null,
    )
}
