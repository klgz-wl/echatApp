package yumo.achat.app.ui.login

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionCacheTest {
    @Test fun clearRunsOnlyAfterRemoteDeletionSucceeds() = runBlocking {
        val calls = mutableListOf<String>()
        completeAccountDeletion({ calls += "server-deleted" }, { calls += "clear" })
        assertEquals(listOf("server-deleted", "clear"), calls)
        calls.clear()
        try { completeAccountDeletion({ error("server rejected") }, { calls += "clear" }) }
        catch (_: IllegalStateException) { }
        assertTrue(calls.isEmpty())
    }
    @Test fun cancellationWhileSuccessfulIoDeletionReturnsStillClearsCache() = runBlocking {
        val serverDeleted = CompletableDeferred<Unit>()
        val returnToUi = CompletableDeferred<Unit>()
        var cleared = false
        val request = launch {
            completeAccountDeletion({
                withContext(Dispatchers.IO) {
                    serverDeleted.complete(Unit)
                    withContext(NonCancellable) { returnToUi.await() }
                }
            }, { cleared = true })
        }
        serverDeleted.await()
        request.cancel()
        returnToUi.complete(Unit)
        request.join()
        assertTrue(cleared)
    }
    @Test fun cancellationAfterConfirmedDeletionCannotSkipCleanup() = runBlocking {
        var cleared = false
        val request = launch {
            completeAccountDeletion({ currentCoroutineContext().cancel() }, { cleared = true })
        }
        request.join()
        assertTrue(cleared)
    }
}
