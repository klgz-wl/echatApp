package yumo.achat.app.ui.login

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionProfileCollectionTest {
    @Test fun `target change cancels old request before new receipt and refreshes new target once`() = runBlocking {
        val receipts = MutableStateFlow<String?>("A")
        val retry = MutableStateFlow(0L)
        val targetRevision = MutableStateFlow(0L)
        var target = "A"
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<String>()
        val requests = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            collectAttributionProfileWork(receipts, retry, targetRevision) { receipt ->
                if (receipt != target) return@collectAttributionProfileWork
                requests += receipt
                if (receipt == "A") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else applied.complete(receipt)
            }
        }
        withTimeout(1_000) {
            entered.await()
            target = "B"
            targetRevision.value++
            cancelled.await()
            assertFalse(applied.isCompleted)
            receipts.value = "B"
            assertEquals("B", applied.await())
        }
        collector.cancelAndJoin()
        assertEquals(listOf("A", "B"), requests)
    }

    @Test fun `retry restarts same receipt while owner change cancels it without applying`() = runBlocking {
        val receipts = MutableStateFlow<String?>("same-result")
        val retry = MutableStateFlow(0L)
        val revision = MutableStateFlow(0L)
        var ownerCurrent = true
        var attempts = 0
        var cancellations = 0
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val collector = launch {
            collectAttributionProfileWork(receipts, retry, revision) {
                if (!ownerCurrent) return@collectAttributionProfileWork
                attempts++
                if (attempts == 1) first.complete(Unit) else second.complete(Unit)
                try { awaitCancellation() } finally { cancellations++ }
            }
        }
        withTimeout(1_000) {
            first.await()
            retry.value++
            second.await()
            assertEquals(1, cancellations)
            ownerCurrent = false
            revision.value++
            while (cancellations < 2) yield()
            assertTrue(collector.isActive)
        }
        collector.cancelAndJoin()
        assertEquals(2, attempts)
    }
}
