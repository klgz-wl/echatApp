package yumo.achat.app.ui.report

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSubmissionTest {
    @Test
    fun `local repository waits between one and two seconds before success`() = runBlocking {
        val waits = mutableListOf<Long>()
        val repository = LocalMediaReportRepository(
            durationMillis = { 1_500L },
            wait = { waits += it },
        )

        repository.submit(MediaReport("template-1"))

        assertEquals(listOf(1_500L), waits)
    }

    @Test
    fun `controller rejects another submit while pending and reports success once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val controller = ReportSubmissionController(MediaReportRepository {
            calls++
            gate.await()
        })
        val first = async { controller.submit(MediaReport("template-1")) }
        yield()

        assertFalse(controller.submit(MediaReport("template-1")))
        assertEquals(ReportSubmissionState.Submitting, controller.state.value)
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, calls)
        assertEquals(ReportSubmissionState.Success, controller.state.value)
    }

    @Test
    fun `cancelling pending submit resets state without reporting success`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val controller = ReportSubmissionController(MediaReportRepository { gate.await() })
        val job = launch { controller.submit(MediaReport("template-1")) }
        yield()

        job.cancelAndJoin()

        assertEquals(ReportSubmissionState.Idle, controller.state.value)
    }
}
