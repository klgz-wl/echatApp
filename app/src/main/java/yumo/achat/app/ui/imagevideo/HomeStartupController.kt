package yumo.achat.app.ui.imagevideo

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Keeps the two Splash waits and the home request gate in their required order. */
internal class HomeStartupController(
    private val awaitPreAuth: suspend () -> Unit,
    private val awaitPostAuth: suspend () -> Unit,
    private val awaitHomeWindow: suspend () -> Unit,
    private val awaitHomeDataGate: suspend () -> Unit,
    private val networkTimeoutMillis: Long,
) {
    suspend fun <S, P> startup(authenticate: suspend () -> S, profile: suspend () -> P): Pair<S, P> {
        awaitPreAuth()
        val session = withTimeout(networkTimeoutMillis) { retryNetworkRequest(authenticate, retryDelayMillis = 250) }
        awaitPostAuth()
        val loadedProfile = withTimeout(networkTimeoutMillis) { retryNetworkRequest(profile, retryDelayMillis = 1_250) }
        return session to loadedProfile
    }

    private suspend fun <T> retryNetworkRequest(request: suspend () -> T, retryDelayMillis: Long): T {
        repeat(2) {
            try {
                return request()
            } catch (_: IOException) {
                delay(retryDelayMillis)
            }
        }
        return request()
    }

    suspend fun <T> loadHome(load: suspend () -> T): T {
        awaitHomeWindow()
        awaitHomeDataGate()
        return load()
    }

    suspend fun <T> loadTemplates(load: suspend () -> T): T {
        awaitHomeWindow()
        awaitHomeDataGate()
        return load()
    }
}
