package yumo.achat.core.attribution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException

/** A page waits on process-owned work; cancelling that page cannot restart acquisition. */
class HomeAttributionGate(
    private val scope: CoroutineScope,
    private val restoredSuccess: () -> Boolean,
    private val verify: suspend () -> Unit,
) {
    private val lock = Any()
    private val entered = CompletableDeferred<Unit>()
    private var verification: Deferred<Unit>? = null

    suspend fun enterAndVerify() {
        if (restoredSuccess()) return
        val task = synchronized(lock) {
            verification ?: scope.async(start = CoroutineStart.LAZY) {
                try { verify() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Acquisition is finite; storage failures are checked by the report gate. */ }
            }.also { verification = it }
        }
        task.start()
        entered.complete(Unit)
        task.await()
    }

    suspend fun awaitVerification() {
        if (restoredSuccess()) return
        entered.await()
        synchronized(lock) { checkNotNull(verification) }.await()
    }
}
