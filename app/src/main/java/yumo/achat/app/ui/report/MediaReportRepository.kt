package yumo.achat.app.ui.report

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

data class MediaReport(val mediaId: String)

fun interface MediaReportRepository {
    suspend fun submit(report: MediaReport)
}

/** Local stand-in for a future report API. Cancellation propagates through delay. */
class LocalMediaReportRepository(
    private val durationMillis: () -> Long = { Random.nextLong(1_000L, 2_001L) },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : MediaReportRepository {
    override suspend fun submit(report: MediaReport) {
        wait(durationMillis())
    }
}

enum class ReportSubmissionState { Idle, Submitting, Success, Failure }

class ReportSubmissionController(private val repository: MediaReportRepository) {
    private val _state = MutableStateFlow(ReportSubmissionState.Idle)
    val state: StateFlow<ReportSubmissionState> = _state

    /** Returns false when another request is already pending or this request fails. */
    suspend fun submit(report: MediaReport): Boolean {
        synchronized(this) {
            if (_state.value == ReportSubmissionState.Submitting) return false
            _state.value = ReportSubmissionState.Submitting
        }
        return try {
            repository.submit(report)
            _state.value = ReportSubmissionState.Success
            true
        } catch (cancelled: CancellationException) {
            _state.value = ReportSubmissionState.Idle
            throw cancelled
        } catch (_: Exception) {
            _state.value = ReportSubmissionState.Failure
            false
        }
    }
}
