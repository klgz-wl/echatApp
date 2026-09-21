package yumo.achat.app.ui.imagevideo

import yumo.achat.app.data.backend.VisualGenerationTask
import yumo.achat.app.data.backend.VisualResource
import yumo.achat.app.data.backend.isVisualGenerationFinished
import yumo.achat.app.data.backend.visualGenerationPollIntervalSeconds

internal data class TrackedGenerationTask(
    val taskId: String,
    val title: String,
    val modality: String,
    val status: String,
    val resultUrl: String?,
    val mimeType: String,
    val errorMessage: String?,
    val pollIntervalSeconds: Int = 3,
) {
    val isFinished: Boolean
        get() = isVisualGenerationFinished(status)

    val canOpenResult: Boolean
        get() = status == "succeeded" && !resultUrl.isNullOrBlank()

    val canPreviewAsImage: Boolean
        get() = canOpenResult && (mimeType.startsWith("image/") || modality == "image")
}

internal fun VisualGenerationTask.toTrackedGenerationTask(title: String): TrackedGenerationTask =
    TrackedGenerationTask(
        taskId = taskId,
        title = title,
        modality = modality,
        status = status,
        resultUrl = resource?.url?.takeIf { it.isNotBlank() },
        mimeType = resource?.mimeType.orEmpty(),
        errorMessage = errorMessage,
        pollIntervalSeconds = visualGenerationPollIntervalSeconds(estimatedPollIntervalSeconds),
    )

internal fun VisualResource.toTrackedGenerationTask(defaultTitle: String): TrackedGenerationTask =
    TrackedGenerationTask(
        taskId = taskId?.takeIf { it.isNotBlank() } ?: id,
        title = templateName?.takeIf { it.isNotBlank() } ?: defaultTitle,
        modality = modality,
        status = "succeeded",
        resultUrl = url.takeIf { it.isNotBlank() },
        mimeType = mimeType,
        errorMessage = null,
    )

internal fun upsertTrackedGenerationTask(
    tasks: List<TrackedGenerationTask>,
    updatedTask: TrackedGenerationTask,
): List<TrackedGenerationTask> = listOf(updatedTask) + tasks.filterNot { it.taskId == updatedTask.taskId }

internal fun mergeTrackedGenerationTasks(
    sessionTasks: List<TrackedGenerationTask>,
    serverHistory: List<TrackedGenerationTask>,
): List<TrackedGenerationTask> {
    val sessionTaskIds = sessionTasks.mapTo(mutableSetOf()) { it.taskId }
    return sessionTasks + serverHistory.filterNot { it.taskId in sessionTaskIds }
}
