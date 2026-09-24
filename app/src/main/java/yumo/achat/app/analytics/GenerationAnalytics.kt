package yumo.achat.app.analytics

import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.backend.AchatBackendHttpException
import yumo.achat.core.backend.VisualGenerationTask

internal data class GenerationAnalyticsContext(
    val templateId: String,
    val categoryId: String,
    val modality: String,
    val source: String,
    val quality: String,
    val quotedDiamondCost: Int,
)

internal class GenerationAnalytics(
    private val events: EventTracker,
    private val userId: () -> String?,
) {
    fun localPrepare(context: GenerationAnalyticsContext, success: Boolean, reason: String? = null) {
        events.track(
            name = "upload_img_result",
            parameters = context.baseProperties() + buildMap {
                put("phase", "local_prepare")
                put("selection_source", "device")
                put("is_success", success)
                reason?.let { put("fail_reason", it) }
            },
            userId = userId(),
        )
    }

    fun clickGenerate(context: GenerationAnalyticsContext, requestId: String) {
        events.track(
            name = "click_generate",
            parameters = context.baseProperties() + mapOf(
                "request_id" to requestId,
                "quality" to context.quality,
                "diamond_cost" to context.quotedDiamondCost.toLong(),
            ),
            userId = userId(),
        )
    }

    fun submitResult(
        context: GenerationAnalyticsContext,
        requestId: String,
        task: VisualGenerationTask?,
        error: Throwable?,
    ) {
        events.track(
            name = "upload_img_result",
            parameters = context.baseProperties() + buildMap {
                put("phase", "task_submit")
                put("request_id", requestId)
                put("is_success", task != null)
                task?.taskId?.let { put("task_id", it) }
                if (task == null) put("fail_reason", controlledGenerationReason(error))
            },
            userId = userId(),
        )
    }

    fun terminalResult(context: GenerationAnalyticsContext, requestId: String, task: VisualGenerationTask) {
        terminalResult(
            context = context,
            requestId = requestId,
            taskId = task.taskId,
            status = task.status,
            quality = task.quality,
            diamondCost = task.diamondCost,
        )
    }

    fun terminalResult(
        context: GenerationAnalyticsContext,
        requestId: String,
        taskId: String,
        status: String,
        quality: String,
        diamondCost: Int,
    ) {
        val succeeded = status == "succeeded"
        events.track(
            name = "generate_result",
            parameters = context.baseProperties() + buildMap {
                put("request_id", requestId)
                put("task_id", taskId)
                put("quality", quality)
                put("diamond_cost", diamondCost.toLong())
                put("is_success", succeeded)
                if (!succeeded) put("fail_reason", "generation_failed")
            },
            userId = userId(),
            onceKey = "generation:$taskId:terminal",
        )
    }

    private fun GenerationAnalyticsContext.baseProperties(): Map<String, Any> = mapOf(
        "template_id" to templateId,
        "category_id" to categoryId,
        "modality" to modality,
        "source" to source,
    )
}

internal fun controlledGenerationReason(error: Throwable?): String = when ((error as? AchatBackendHttpException)?.statusCode) {
    400 -> "invalid_image"
    401, 403 -> "signed_out"
    402 -> "insufficient"
    409 -> "conflict"
    in 500..599 -> "unavailable"
    else -> "unknown"
}
