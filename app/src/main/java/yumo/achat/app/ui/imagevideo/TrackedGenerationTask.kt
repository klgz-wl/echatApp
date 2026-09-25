package yumo.achat.app.ui.imagevideo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import yumo.achat.core.backend.VisualGenerationTask
import yumo.achat.core.backend.VisualResource
import yumo.achat.core.backend.isVisualGenerationFinished
import yumo.achat.core.backend.visualGenerationPollIntervalSeconds

internal data class TrackedGenerationTask(
    val taskId: String,
    val title: String,
    val modality: String,
    val status: String,
    val resultUrl: String?,
    val mimeType: String,
    val errorMessage: String?,
    val thumbnailUrl: String? = null,
    val pollIntervalSeconds: Int = 3,
    val requestId: String = "",
    val templateId: String = "",
    val categoryId: String = "",
    val quality: String = "",
    val diamondCost: Int = 0,
    val source: String = "",
) {
    val isFinished: Boolean
        get() = isVisualGenerationFinished(status)

    val canOpenResult: Boolean
        get() = status == "succeeded" && !resultUrl.isNullOrBlank()

    val canPreviewAsImage: Boolean
        get() = canOpenResult && (mimeType.startsWith("image/") || modality == "image")
}

internal fun VisualGenerationTask.toTrackedGenerationTask(
    title: String,
    previous: TrackedGenerationTask? = null,
): TrackedGenerationTask =
    TrackedGenerationTask(
        taskId = taskId,
        title = title,
        modality = modality,
        status = status,
        resultUrl = resource?.url?.takeIf { it.isNotBlank() },
        thumbnailUrl = resource?.thumbnailUrl?.takeIf { it.isNotBlank() },
        mimeType = resource?.mimeType.orEmpty(),
        errorMessage = errorMessage,
        pollIntervalSeconds = visualGenerationPollIntervalSeconds(estimatedPollIntervalSeconds),
        requestId = previous?.requestId.orEmpty(),
        templateId = templateId,
        categoryId = previous?.categoryId.orEmpty(),
        quality = quality,
        diamondCost = diamondCost,
        source = previous?.source.orEmpty(),
    )

internal fun VisualResource.toTrackedGenerationTask(defaultTitle: String): TrackedGenerationTask =
    TrackedGenerationTask(
        taskId = taskId?.takeIf { it.isNotBlank() } ?: id,
        title = templateName?.takeIf { it.isNotBlank() } ?: defaultTitle,
        modality = modality,
        status = "succeeded",
        resultUrl = url.takeIf { it.isNotBlank() },
        thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
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

internal fun encodeTrackedGenerationTasks(tasks: List<TrackedGenerationTask>): String {
    val array = JSONArray()
    tasks.forEach { task ->
        array.put(
            JSONObject()
                .put("task_id", task.taskId)
                .put("title", task.title)
                .put("modality", task.modality)
                .put("status", task.status)
                .put("result_url", task.resultUrl)
                .put("thumbnail_url", task.thumbnailUrl)
                .put("mime_type", task.mimeType)
                .put("error_message", task.errorMessage)
                .put("poll_interval_seconds", task.pollIntervalSeconds)
                .put("request_id", task.requestId)
                .put("template_id", task.templateId)
                .put("category_id", task.categoryId)
                .put("quality", task.quality)
                .put("diamond_cost", task.diamondCost)
                .put("source", task.source),
        )
    }
    return array.toString()
}

internal fun decodeTrackedGenerationTasks(value: String?): List<TrackedGenerationTask> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val taskId = item.optString("task_id").takeIf { it.isNotBlank() } ?: continue
                add(
                    TrackedGenerationTask(
                        taskId = taskId,
                        title = item.optString("title").takeIf { it.isNotBlank() } ?: "Visual generation",
                        modality = item.optString("modality"),
                        status = item.optString("status").takeIf { it.isNotBlank() } ?: "processing",
                        resultUrl = item.optNullableString("result_url"),
                        thumbnailUrl = item.optNullableString("thumbnail_url"),
                        mimeType = item.optString("mime_type"),
                        errorMessage = item.optNullableString("error_message"),
                        pollIntervalSeconds = item.optInt("poll_interval_seconds", 3).coerceAtLeast(1),
                        requestId = item.optString("request_id"),
                        templateId = item.optString("template_id"),
                        categoryId = item.optString("category_id"),
                        quality = item.optString("quality"),
                        diamondCost = item.optInt("diamond_cost", 0),
                        source = item.optString("source"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal fun tasksForLocalSnapshot(tasks: List<TrackedGenerationTask>, limit: Int = 30): List<TrackedGenerationTask> =
    tasks.filter { it.taskId.isNotBlank() }
        .take(limit.coerceAtLeast(1))

internal class LocalGenerationTaskStore(context: Context) {
    private val preferences = context.getSharedPreferences("achat_generation_tasks", Context.MODE_PRIVATE)

    fun read(): List<TrackedGenerationTask> =
        decodeTrackedGenerationTasks(preferences.getString(KEY_TASKS, null))

    fun write(tasks: List<TrackedGenerationTask>) {
        preferences.edit()
            .putString(KEY_TASKS, encodeTrackedGenerationTasks(tasksForLocalSnapshot(tasks)))
            .apply()
    }

    private companion object {
        const val KEY_TASKS = "tracked_tasks"
    }
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal const val MyTaskVideoPreloadBytes = TemplateCurrentVideoPreloadBytes

internal fun List<TrackedGenerationTask>.myTaskImagePrefetchUrls(): List<String> =
    filter { it.canOpenResult }
        .flatMap { task ->
            buildList {
                task.thumbnailUrl?.takeIf { it.isNotBlank() }?.let(::add)
                task.resultUrl?.takeIf { task.canPreviewAsImage && it.isNotBlank() }?.let(::add)
            }
        }
        .distinct()

internal fun shouldShowMyTaskImageLoading(hasRenderedImage: Boolean, hasImageError: Boolean): Boolean =
    !hasRenderedImage && !hasImageError

internal fun List<TrackedGenerationTask>.myTaskVideoPreloadTargets(): List<TemplateVideoPreloadTarget> =
    filter { it.canOpenResult && (it.mimeType.startsWith("video/") || it.modality == "video") }
        .mapNotNull { task ->
            task.resultUrl?.takeIf { it.isNotBlank() }?.let { url ->
                TemplateVideoPreloadTarget(url, MyTaskVideoPreloadBytes)
            }
        }
        .distinctBy { it.url }
