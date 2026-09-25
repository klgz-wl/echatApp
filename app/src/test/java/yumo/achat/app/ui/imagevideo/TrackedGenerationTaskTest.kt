package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.backend.VisualGenerationTask
import yumo.achat.core.backend.VisualResource

class TrackedGenerationTaskTest {
    @Test
    fun `home shows latest generating and completed task badges`() {
        val base = TrackedGenerationTask(
            taskId = "task-base",
            title = "Template",
            modality = "video",
            status = "processing",
            resultUrl = null,
            mimeType = "",
            errorMessage = null,
        )
        val tasks = listOf(
            base.copy(taskId = "failed", status = "failed"),
            base.copy(taskId = "generating", status = "processing"),
            base.copy(taskId = "completed", status = "succeeded"),
            base.copy(taskId = "older-generating", status = "queued"),
        )

        assertEquals(
            listOf(HomeTaskStatusBadge.Generating, HomeTaskStatusBadge.Completed),
            homeTaskStatusBadges(tasks),
        )
        assertEquals(emptyList<HomeTaskStatusBadge>(), homeTaskStatusBadges(listOf(tasks.first())))
    }

    @Test
    fun `successful task exposes generated result url`() {
        val task = VisualGenerationTask(
            taskId = "task-12345678",
            status = "succeeded",
            modality = "image",
            quality = "fast",
            templateId = "template-1",
            diamondCost = 22,
            estimatedPollIntervalSeconds = 5,
            refunded = false,
            errorMessage = null,
            resource = VisualResource(
                id = "resource-1",
                taskId = "task-12345678",
                resourceType = "generated",
                modality = "image",
                url = "https://example.test/generated.webp",
                thumbnailUrl = "https://example.test/thumb.webp",
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
        assertEquals("https://example.test/thumb.webp", trackedTask.thumbnailUrl)
        assertEquals("image/webp", trackedTask.mimeType)
        assertTrue(trackedTask.isFinished)
        assertTrue(trackedTask.canOpenResult)
        assertEquals(5, trackedTask.pollIntervalSeconds)
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
    fun `late processing response cannot revive a terminal task`() {
        val terminal = TrackedGenerationTask(
            taskId = "task-1",
            title = "Template",
            modality = "video",
            status = "succeeded",
            resultUrl = "https://example.test/result.mp4",
            mimeType = "video/mp4",
            errorMessage = null,
        )
        val stale = terminal.copy(status = "processing", resultUrl = null, mimeType = "")

        assertEquals(listOf(terminal), upsertTrackedGenerationTask(listOf(terminal), stale))
    }

    @Test
    fun `server history merge preserves response order and keeps live task first`() {
        val liveTask = TrackedGenerationTask(
            taskId = "task-live",
            title = "Live",
            modality = "image",
            status = "processing",
            resultUrl = null,
            mimeType = "",
            errorMessage = null,
        )
        val newestHistory = liveTask.copy(taskId = "task-newest", title = "Newest", status = "succeeded")
        val oldestHistory = newestHistory.copy(taskId = "task-oldest", title = "Oldest")

        val merged = mergeTrackedGenerationTasks(
            sessionTasks = listOf(liveTask),
            serverHistory = listOf(newestHistory, oldestHistory),
        )

        assertEquals(listOf("task-live", "task-newest", "task-oldest"), merged.map { it.taskId })
    }

    @Test
    fun `tracked task snapshots preserve processing video tasks for local restore`() {
        val processingVideo = TrackedGenerationTask(
            taskId = "task-video-processing",
            title = "Video template",
            modality = "video",
            status = "processing",
            resultUrl = null,
            thumbnailUrl = null,
            mimeType = "",
            errorMessage = null,
            pollIntervalSeconds = 7,
            requestId = "request-1",
            templateId = "template-video",
            categoryId = "category-video",
            quality = "fast",
            diamondCost = 10,
            source = "video",
        )

        val restored = decodeTrackedGenerationTasks(encodeTrackedGenerationTasks(listOf(processingVideo)))

        assertEquals(listOf(processingVideo), restored)
        assertFalse(restored.single().isFinished)
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
            thumbnailUrl = "https://example.test/generated-thumb.webp",
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
        assertEquals("https://example.test/generated-thumb.webp", trackedTask.thumbnailUrl)
        assertTrue(trackedTask.canOpenResult)
    }

    @Test
    fun `my tasks media prefetch prefers thumbnails and video result prefixes`() {
        val image = TrackedGenerationTask(
            taskId = "task-image",
            title = "Image",
            modality = "image",
            status = "succeeded",
            resultUrl = "https://example.test/full.webp",
            thumbnailUrl = "https://example.test/thumb.webp",
            mimeType = "image/webp",
            errorMessage = null,
        )
        val video = TrackedGenerationTask(
            taskId = "task-video",
            title = "Video",
            modality = "video",
            status = "succeeded",
            resultUrl = "https://example.test/video.mp4",
            thumbnailUrl = "https://example.test/video-poster.webp",
            mimeType = "video/mp4",
            errorMessage = null,
        )
        val processing = image.copy(taskId = "task-processing", status = "processing", resultUrl = null)

        assertEquals(
            listOf(
                "https://example.test/thumb.webp",
                "https://example.test/full.webp",
                "https://example.test/video-poster.webp",
            ),
            listOf(image, video, processing).myTaskImagePrefetchUrls(),
        )
        assertEquals(
            listOf(TemplateVideoPreloadTarget("https://example.test/video.mp4", MyTaskVideoPreloadBytes)),
            listOf(image, video, processing).myTaskVideoPreloadTargets(),
        )
    }

    @Test
    fun `my task image loading indicator hides after image success or error`() {
        assertTrue(shouldShowMyTaskImageLoading(hasRenderedImage = false, hasImageError = false))
        assertFalse(shouldShowMyTaskImageLoading(hasRenderedImage = true, hasImageError = false))
        assertFalse(shouldShowMyTaskImageLoading(hasRenderedImage = false, hasImageError = true))
    }
}
