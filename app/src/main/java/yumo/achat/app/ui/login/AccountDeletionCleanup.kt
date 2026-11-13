package yumo.achat.app.ui.login

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Finish an explicit delete and its successful cleanup even if the initiating page is disposed. */
internal suspend fun completeAccountDeletion(deleteRemote: suspend () -> Unit, clearAccountCache: () -> Unit) {
    currentCoroutineContext().ensureActive()
    withContext(NonCancellable) {
        deleteRemote()
        clearAccountCache()
    }
}
