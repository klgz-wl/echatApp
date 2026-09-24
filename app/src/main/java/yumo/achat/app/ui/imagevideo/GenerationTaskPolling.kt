package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.VisualGenerationTask
import java.util.UUID

internal fun shouldApplyGenerationCharge(taskId: String, chargedTaskIds: Set<String>): Boolean =
    taskId.isNotBlank() && taskId !in chargedTaskIds

internal fun shouldApplyCurrencySnapshot(responseSerial: Long, latestSerial: Long): Boolean =
    responseSerial == latestSerial

internal data class GenerationSubmissionKey(
    val templateId: String,
    val quality: String,
    val resourceId: String,
    val idempotencyKey: String,
) : java.io.Serializable

internal fun nextGenerationSubmissionKey(
    previous: GenerationSubmissionKey?,
    templateId: String,
    quality: String,
    resourceId: String,
    newKey: () -> String = { UUID.randomUUID().toString() },
): GenerationSubmissionKey {
    if (previous != null &&
        previous.templateId == templateId &&
        previous.quality == quality &&
        previous.resourceId == resourceId
    ) {
        return previous
    }
    return GenerationSubmissionKey(
        templateId = templateId,
        quality = quality,
        resourceId = resourceId,
        idempotencyKey = newKey(),
    )
}

internal suspend fun pollGenerationTaskUntilFinished(
    initialTask: TrackedGenerationTask,
    waitForNextPoll: suspend (Int) -> Unit,
    fetch: suspend (String) -> VisualGenerationTask,
    onUpdate: (TrackedGenerationTask) -> Unit,
): TrackedGenerationTask {
    var latest = initialTask
    while (!latest.isFinished) {
        waitForNextPoll(latest.pollIntervalSeconds)
        val response = try {
            fetch(latest.taskId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            continue
        }
        latest = response.toTrackedGenerationTask(latest.title, previous = latest)
        onUpdate(latest)
    }
    return latest
}
