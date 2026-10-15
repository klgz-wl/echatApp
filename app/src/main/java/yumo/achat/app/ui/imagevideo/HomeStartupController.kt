package yumo.achat.app.ui.imagevideo

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
        val session = withTimeout(networkTimeoutMillis) { authenticate() }
        awaitPostAuth()
        val loadedProfile = withTimeout(networkTimeoutMillis) { profile() }
        return session to loadedProfile
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
