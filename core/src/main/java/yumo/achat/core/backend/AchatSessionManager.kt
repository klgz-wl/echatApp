package yumo.achat.core.backend

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val AuthFailureCooldownMillis = 1_000L

private data class TimedAuthFailure(
    val token: String?,
    val timestampMillis: Long,
    val error: Exception,
)

internal class AchatSessionManager(
    private val store: AuthSessionStore,
    private val authApi: AchatAuthApi,
    private val attribution: BackendAttribution = NoOpBackendAttribution(AchatBackendConfiguration.Default),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onLoginResult: (Boolean, String?, String?) -> Unit = { _, _, _ -> },
) {
    private val sessionMutex = Mutex()
    private val attributionReportGuard = Any()
    private var recoveryFailure: TimedAuthFailure? = null
    private var lastAttributionReportEpoch: String? = null
    private val mutableSessionInvalidated = MutableStateFlow(false)
    val sessionInvalidated: StateFlow<Boolean> = mutableSessionInvalidated.asStateFlow()

    suspend fun session(): AuthSession? = store.readSession()

    suspend fun startupSession(): AuthSession? = store.readSession()?.also { reportAttribution(it) }

    suspend fun loginAnonymouslyExplicit(): AuthSession = sessionMutex.withLock {
        check(store.readSession() == null) { "Already signed in" }
        val deviceId = store.prepareAnonymousDeviceId()
        establishSession { authApi.loginAnonymously(deviceId, attribution.forLogin(deviceId)) }
    }

    suspend fun register(username: String, password: String): AuthSession = sessionMutex.withLock {
        check(store.readSession() == null) { "Already signed in" }
        require(username.length in 3..50 && username.none(Char::isWhitespace)) { "Invalid account" }
        require(password.length in 6..18 && password.none(Char::isWhitespace)) { "Invalid password" }
        val deviceId = store.deviceId()
        establishSession { authApi.register(username, password, deviceId, attribution.forLogin(deviceId)) }
    }

    suspend fun logout(deleteAccount: Boolean = false) = sessionMutex.withLock {
        val active = store.readSession() ?: throw SignedOutException()
        val action: (String) -> Unit = if (deleteAccount) authApi::deleteAccount else authApi::logout
        try {
            action(active.token)
        } catch (error: AchatBackendHttpException) {
            if (error.statusCode != 401) throw error
            val recovered = recoverAfterUnauthorizedLocked(active)
            action(recovered.token)
        }
        store.clearSession()
        recoveryFailure = null
    }

    private suspend fun establishSession(login: suspend () -> AuthSession): AuthSession = try {
        login().also { session ->
            store.saveSession(session)
            mutableSessionInvalidated.value = false
            reportAttribution(session)
            onLoginResult(true, null, session.userId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onLoginResult(false, if (error is java.io.IOException) "offline" else "authentication_unavailable", null)
        throw error
    }

    suspend fun <T> authenticated(operation: (String) -> T): T {
        val session = store.readSession() ?: throw SignedOutException()
        try {
            return operation(session.token)
        } catch (error: AchatBackendHttpException) {
            if (error.statusCode != 401) throw error
        }

        val recovered = recoverAfterUnauthorized(session)
        return operation(recovered.token)
    }

    private suspend fun recoverAfterUnauthorized(failedSession: AuthSession): AuthSession =
        sessionMutex.withLock { recoverAfterUnauthorizedLocked(failedSession) }

    private suspend fun recoverAfterUnauthorizedLocked(failedSession: AuthSession): AuthSession {
        val latest = store.readSession()
        if (latest == null) throw SignedOutException()
        if (latest.token != failedSession.token) {
            return latest
        }

        val sessionToRecover = latest
        recentFailure(recoveryFailure, sessionToRecover.token)?.let { throw it }
        try {
            val recovered = try {
                val newAccessToken = authApi.refreshAccessToken(sessionToRecover.refreshToken)
                sessionToRecover.copy(token = newAccessToken)
            } catch (error: AchatBackendHttpException) {
                if (error.statusCode != 400 && error.statusCode != 401) throw error
                store.clearSession()
                mutableSessionInvalidated.value = true
                throw SignedOutException()
            }
            return recovered.also { session ->
                store.saveSession(session)
                reportAttribution(session)
                recoveryFailure = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recoveryFailure = TimedAuthFailure(sessionToRecover.token, nowMillis(), error)
            throw error
        }
    }

    private fun recentFailure(failure: TimedAuthFailure?, token: String?): Exception? = failure
        ?.takeIf { it.token == token && nowMillis() - it.timestampMillis < AuthFailureCooldownMillis }
        ?.error

    private suspend fun reportAttribution(session: AuthSession) {
        val shouldReport = synchronized(attributionReportGuard) {
            if (lastAttributionReportEpoch == session.epoch) false
            else {
                lastAttributionReportEpoch = session.epoch
                true
            }
        }
        if (!shouldReport) return
        try {
            attribution.report(session)
        } catch (_: Exception) {
            synchronized(attributionReportGuard) {
                if (lastAttributionReportEpoch == session.epoch) lastAttributionReportEpoch = null
            }
            // Attribution is telemetry; a report retry/failure must not break authentication.
        }
    }

    companion object {
        @Volatile private var applicationInstance: AchatSessionManager? = null

        fun application(
            context: Context,
            configuration: AchatBackendConfiguration = AchatBackendConfiguration.Default,
            attribution: BackendAttribution = NoOpBackendAttribution(configuration),
            onLoginResult: (Boolean, String?, String?) -> Unit = { _, _, _ -> },
        ): AchatSessionManager =
            applicationInstance ?: synchronized(this) {
                applicationInstance ?: run {
                    val appContext = context.applicationContext
                    AchatSessionManager(
                        store = AchatSessionStore(appContext),
                        authApi = AchatBackendClient(configuration),
                        attribution = attribution,
                        onLoginResult = onLoginResult,
                    ).also { applicationInstance = it }
                }
            }
    }
}

class SignedOutException : IllegalStateException("Sign in required")
