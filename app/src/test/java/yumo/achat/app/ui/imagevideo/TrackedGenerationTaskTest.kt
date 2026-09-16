package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.app.data.backend.VisualGenerationTask
import yumo.achat.app.data.backend.VisualResource

class TrackedGenerationTaskTest {
    @Test
    fun `successful task exposes generated result url`() {
        val task = VisualGenerationTask(
            taskId = "task-12345678",
            status = "succeeded",
            modality = "image",
            quality = "fast",
            templateId = "template-1",
            diamondCost = 22,
            estimatedPollIntervalSeconds = null,
            refunded = false,
            errorMessage = null,
            resource = VisualResource(
                id = "resource-1",
                taskId = "task-12345678",
                resourceType = "generated",
                modality = "image",
                url = "https://example.test/generated.webp",
                thumbnailUrl = null,
                mimeType = "image/webp",
                width = 720,
                height = 1280,
                durationSeconds = 0,
            ),
        )

        val trackedTask = task.toTrackedGenerationTask("Rose template")

        assertEquals("task-12345678", trackedTask.taskId)
        assertEquals("Rose template", trackedTask.title)
        assertEquals("https://example.test/generated.webp", trackedTask.resultUrl)
        assertEquals("image/webp", trackedTask.mimeType)
        assertTrue(trackedTask.isFinished)
        assertTrue(trackedTask.canOpenResult)
    }

    @Test
    fun `failed task keeps error but cannot open result`() {
        val task = VisualGenerationTask(
            taskId = "task-failed",
            status = "failed",
            modality = "video",
            quality = "quality",
            templateId = "template-2",
            diamondCost = 44,
            estimatedPollIntervalSeconds = null,
            refunded = true,
            errorMessage = "Source image is invalid",
            resource = null,
        )

        val trackedTask = task.toTrackedGenerationTask("Video template")

        assertEquals("Source image is invalid", trackedTask.errorMessage)
        assertTrue(trackedTask.isFinished)
        assertFalse(trackedTask.canOpenResult)
    }

    @Test
    fun `upsert tracked task keeps latest status first`() {
        val processing = TrackedGenerationTask(
            taskId = "task-1",
            title = "Template",
            modality = "image",
            status = "processing",
            resultUrl = null,
            mimeType = "",
            errorMessage = null,
        )
        val succeeded = processing.copy(
            status = "succeeded",
            resultUrl = "https://example.test/result.webp",
            mimeType = "image/webp",
        )
        val older = TrackedGenerationTask(
            taskId = "task-2",
            title = "Older",
            modality = "video",
            status = "processing",
            resultUrl = null,
            mimeType = "",
            errorMessage = null,
        )

        val tasks = upsertTrackedGenerationTask(listOf(processing, older), succeeded)

        assertEquals(listOf("task-1", "task-2"), tasks.map { it.taskId })
        assertEquals("succeeded", tasks.first().status)
        assertEquals("https://example.test/result.webp", tasks.first().resultUrl)
    }

    @Test
    fun `generated resource maps to successful tracked task`() {
        val resource = VisualResource(
            id = "generated-1",
            taskId = null,
            resourceType = "generated",
            modality = "image",
            templateId = "template-1",
            templateName = "Rose portrait",
            url = "https://example.test/generated.webp",
            thumbnailUrl = null,
            mimeType = "image/webp",
            width = 720,
            height = 1280,
            durationSeconds = 0,
            createdAt = "2026-09-16T08:00:00Z",
            expiresAt = null,
        )

        val trackedTask = resource.toTrackedGenerationTask(defaultTitle = "Visual generation")

        assertEquals("generated-1", trackedTask.taskId)
        assertEquals("Rose portrait", trackedTask.title)
        assertEquals("succeeded", trackedTask.status)
        assertEquals("https://example.test/generated.webp", trackedTask.resultUrl)
        assertTrue(trackedTask.canOpenResult)
    }
}
