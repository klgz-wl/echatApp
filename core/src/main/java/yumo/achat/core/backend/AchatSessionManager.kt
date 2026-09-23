package yumo.achat.core.backend

import android.content.Context
import kotlinx.coroutines.CancellationException
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
) {
    private val sessionMutex = Mutex()
    private val attributionReportGuard = Any()
    private var loginFailure: TimedAuthFailure? = null
    private var recoveryFailure: TimedAuthFailure? = null
    private var lastAttributionReportToken: String? = null

    suspend fun session(): AuthSession = ensureSession()

    suspend fun <T> authenticated(operation: (String) -> T): T {
        val session = ensureSession()
        try {
            return operation(session.token)
        } catch (error: AchatBackendHttpException) {
            if (error.statusCode != 401) throw error
        }

        val recovered = recoverAfterUnauthorized(session)
        return operation(recovered.token)
    }

    private suspend fun ensureSession(): AuthSession {
        store.readSession()?.let {
            reportAttribution(it)
            return it
        }
        return sessionMutex.withLock {
            store.readSession()?.let {
                reportAttribution(it)
                return@withLock it
            }
            recentFailure(loginFailure, token = null)?.let { throw it }
            try {
                loginAnonymously().also { session ->
                    store.saveSession(session)
                    reportAttribution(session)
                    loginFailure = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                loginFailure = TimedAuthFailure(null, nowMillis(), error)
                throw error
            }
        }
    }

    private suspend fun recoverAfterUnauthorized(failedSession: AuthSession): AuthSession =
        sessionMutex.withLock {
            val latest = store.readSession()
            if (latest != null && latest.token != failedSession.token) {
                return@withLock latest
            }

            val sessionToRecover = latest ?: failedSession
            recentFailure(recoveryFailure, sessionToRecover.token)?.let { throw it }
            try {
                val recovered = try {
                    val newAccessToken = authApi.refreshAccessToken(sessionToRecover.refreshToken)
                    sessionToRecover.copy(token = newAccessToken)
                } catch (error: AchatBackendHttpException) {
                    if (error.statusCode != 400 && error.statusCode != 401) throw error
                    loginAnonymously()
                }
                recovered.also { session ->
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

    private suspend fun loginAnonymously(): AuthSession {
        val installDeviceId = store.deviceId()
        return authApi.loginAnonymously(installDeviceId, attribution.forLogin(installDeviceId))
    }

    private suspend fun reportAttribution(session: AuthSession) {
        val shouldReport = synchronized(attributionReportGuard) {
            if (lastAttributionReportToken == session.token) false
            else {
                lastAttributionReportToken = session.token
                true
            }
        }
        if (!shouldReport) return
        try {
            attribution.report(session)
        } catch (_: Exception) {
            synchronized(attributionReportGuard) {
                if (lastAttributionReportToken == session.token) lastAttributionReportToken = null
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
        ): AchatSessionManager =
            applicationInstance ?: synchronized(this) {
                applicationInstance ?: run {
                    val appContext = context.applicationContext
                    AchatSessionManager(
                        store = AchatSessionStore(appContext),
                        authApi = AchatBackendClient(configuration),
                        attribution = attribution,
                    ).also { applicationInstance = it }
                }
            }
    }
}
