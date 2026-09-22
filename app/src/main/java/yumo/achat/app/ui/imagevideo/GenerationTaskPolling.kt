package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.VisualGenerationTask

internal fun shouldApplyGenerationCharge(taskId: String, chargedTaskIds: Set<String>): Boolean =
    taskId.isNotBlank() && taskId !in chargedTaskIds

internal fun shouldApplyCurrencySnapshot(responseSerial: Long, latestSerial: Long): Boolean =
    responseSerial == latestSerial

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
        latest = response.toTrackedGenerationTask(latest.title)
        onUpdate(latest)
    }
    return latest
}
