package yumo.achat.app.ui.imagevideo

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the two Splash waits and the home request gate in their required order. */
internal class HomeStartupController(
    private val awaitPreAuth: suspend () -> Unit,
    private val awaitPostAuth: suspend () -> Unit,
    private val awaitHomeWindow: suspend () -> Unit,
    private val awaitHomeDataGate: suspend () -> Unit,
    private val networkTimeoutMillis: Long,
    private val attributionCompleted: () -> Boolean = { false },
    private val beginAuthentication: suspend () -> Unit = {},
) {
    suspend fun <S, P> startup(
        authenticate: suspend () -> S,
        profile: suspend () -> P,
        prepare: suspend () -> Unit = {},
    ): Pair<S, P> {
        prepare()
        beginAuthentication()
        if (!attributionCompleted()) awaitPreAuth()
        val session = networkOperation { retryNetworkRequest(authenticate, retryDelayMillis = 250) }
        return resumeAfterAuthentication(session, profile)
    }

    suspend fun <S, P> restoreSession(session: S, profile: suspend () -> P): Pair<S, P> =
        session to networkOperation { retryNetworkRequest(profile, retryDelayMillis = 1_250) }

    suspend fun <P> refreshAttributionProfile(
        attempts: Int,
        retryIntervalMillis: Long,
        isCurrent: () -> Boolean,
        profile: suspend () -> P,
    ): P? {
        if (!isCurrent()) return null
        awaitHomeDataGate()
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (!isCurrent()) return null
            val result = try {
                networkOperation { profile() }
            } catch (_: StartupOperationTimeout) {
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isCurrent()) return null
            if (result != null) return result
            if (attempt + 1 < attempts) delay(retryIntervalMillis)
        }
        throw AttributionProfileRefreshExhausted()
    }

    suspend fun <S, P> resumeAfterAuthentication(session: S, profile: suspend () -> P): Pair<S, P> {
        if (!attributionCompleted()) awaitPostAuth()
        val loadedProfile = networkOperation { retryNetworkRequest(profile, retryDelayMillis = 1_250) }
        return session to loadedProfile
    }

    private suspend fun <T> networkOperation(request: suspend () -> T): T {
        // The wrapper preserves nullable results and converts only this operation's own timeout.
        val result = try {
            withTimeoutOrNull(networkTimeoutMillis) { NetworkResult(request()) }
                ?: throw StartupOperationTimeout()
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw StartupOperationTimeout()
        }
        return result.value
    }

    private data class NetworkResult<T>(val value: T)

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
        if (!attributionCompleted()) {
            awaitHomeWindow()
            awaitHomeDataGate()
        }
        return load()
    }

    suspend fun <T> loadTemplates(load: suspend () -> T): T {
        if (!attributionCompleted()) {
            awaitHomeWindow()
            awaitHomeDataGate()
        }
        return load()
    }
}

internal class AttributionProfileRefreshExhausted : IOException("Attribution profile refresh exhausted")

internal class StartupOperationTimeout : Exception("Startup operation timed out")
