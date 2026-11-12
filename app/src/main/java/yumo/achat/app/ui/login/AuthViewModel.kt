package yumo.achat.app.ui.login

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import yumo.achat.app.BuildConfig
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.attribution.AppsFlyerBackendAttribution
import yumo.achat.app.ui.imagevideo.HomeStartupController
import yumo.achat.app.ui.imagevideo.createAchatRepository
import yumo.achat.core.backend.AuthSession
import yumo.achat.core.backend.UserProfile

/** UI reads gate ownership through this facade; only the ViewModel starts acquisition. */
internal class AuthAttributionUi(private val runtime: AppsFlyerBackendAttribution) {
    val homeGateRevision get() = runtime.homeGateRevision
    fun currentSessionEpoch() = runtime.currentSessionEpoch()
    fun currentGateTarget() = runtime.currentGateTarget()
}

internal class AuthViewModel(application: Application) : AndroidViewModel(application) {
    val repository = createAchatRepository(application)
    private var acquisitionAuthorized by mutableStateOf(AnalyticsConsent.granted(application))
    private val configuredRuntime by lazy { AchatAttributionRuntime.get(application) }
    private val runtime get() = if (acquisitionAuthorized && BuildConfig.ENABLE_APPSFLYER) configuredRuntime else null
    private val attributionUi by lazy { AuthAttributionUi(configuredRuntime) }
    val attribution get() = if (acquisitionAuthorized && BuildConfig.ENABLE_APPSFLYER) attributionUi else null
    val sessionInvalidated = repository.sessionInvalidated
    private val startupController = HomeStartupController(
        awaitPreAuth = { runtime?.awaitSplashPreAuthWindow() },
        awaitPostAuth = { runtime?.awaitSplashPostAuthWindow() },
        awaitHomeWindow = { runtime?.awaitHomeWindow() },
        awaitHomeDataGate = { runtime?.awaitHomeDataGate() },
        networkTimeoutMillis = BuildConfig.STARTUP_TIMEOUT_MS.toLong(),
        attributionCompleted = { runtime?.isAttributionComplete() == true },
        beginAuthentication = { runtime?.beginAuthentication() },
    )
    private var pendingAuthenticatedSession: AuthSession? = null
    private var operationGeneration = 0L
    val hasAuthenticatedSession: Boolean get() = pendingAuthenticatedSession != null
    var attributionProfileRetryRequired by mutableStateOf(false)
        private set

    suspend fun authenticateStartup(onSessionReady: () -> Unit): Pair<AuthSession, UserProfile>? {
        val generation = operationGeneration
        val session = repository.authenticateStartup()
        checkOperation(generation)
        if (session == null) {
            pauseForLoginChoice()
            return null
        }
        val resumePendingAuthentication = pendingAuthenticatedSession?.epoch == session.epoch
        onSessionReady()
        runtime?.resumeRestoredSessionAttribution()
        val result = if (resumePendingAuthentication) {
            startupController.resumeAfterAuthentication(session, repository::loadStartupProfile)
        } else {
            startupController.restoreSession(session, repository::loadStartupProfile)
        }
        checkOperation(generation)
        pendingAuthenticatedSession = null
        return result
    }

    suspend fun loginAnonymously(onAuthFlowStart: () -> Unit): Pair<AuthSession, UserProfile> =
        submit(null, null, onAuthFlowStart)

    suspend fun register(account: String, password: String, onAuthFlowStart: () -> Unit): Pair<AuthSession, UserProfile> =
        submit(account, password, onAuthFlowStart)

    private suspend fun submit(account: String?, password: String?, onAuthFlowStart: () -> Unit): Pair<AuthSession, UserProfile> {
        val generation = operationGeneration
        return try {
            submitOwned(account, password, onAuthFlowStart, generation)
        } catch (error: Exception) {
            pendingAuthenticatedSession = reconcileAuthenticationFailure(
                error = error,
                checkOperation = { checkOperation(generation) },
                savedSession = repository::savedSession,
            )
            throw error
        }
    }

    private suspend fun submitOwned(
        account: String?,
        password: String?,
        onAuthFlowStart: () -> Unit,
        generation: Long,
    ): Pair<AuthSession, UserProfile> {
        val saved = repository.authenticateStartup()
        checkOperation(generation)
        val result = if (saved != null) {
            val resumePendingAuthentication = pendingAuthenticatedSession?.epoch == saved.epoch
            pendingAuthenticatedSession = saved
            acquisitionAuthorized = true
            onAuthFlowStart()
            runtime?.resumeRestoredSessionAttribution()
            if (resumePendingAuthentication) {
                startupController.resumeAfterAuthentication(saved, repository::loadStartupProfile)
            } else {
                startupController.restoreSession(saved, repository::loadStartupProfile)
            }
        } else {
            startupController.startup(
                prepare = {
                    if (account == null) repository.prepareAnonymousDeviceIdentity()
                    else repository.prepareAccountDeviceIdentity()
                    checkOperation(generation)
                    acquisitionAuthorized = true
                    onAuthFlowStart()
                },
                authenticate = {
                    (if (account == null) repository.loginAnonymously()
                    else repository.register(account, requireNotNull(password))).also {
                        checkOperation(generation)
                        pendingAuthenticatedSession = it
                    }
                },
                profile = repository::loadStartupProfile,
            )
        }
        checkOperation(generation)
        pendingAuthenticatedSession = null
        return result
    }

    fun pauseForLoginChoice() {
        operationGeneration++
        pendingAuthenticatedSession = null
        attributionProfileRetryRequired = false
        runtime?.pauseForLoginChoice()
    }

    suspend fun onSignedOut() {
        pauseForLoginChoice()
        runtime?.onSignedOut()
    }

    suspend fun logout(delete: Boolean, onDeleted: () -> Unit = {}) {
        if (delete) completeAccountDeletion(repository::deleteAccount, onDeleted) else repository.logout()
        onSignedOut()
    }

    suspend fun <T> loadHome(load: suspend () -> T): T = startupController.loadHome(load)
    suspend fun <T> loadTemplates(load: suspend () -> T): T = startupController.loadTemplates(load)

    fun retryHome() {
        if (attributionProfileRetryRequired) retryProfileRefresh() else runtime?.retryHomeReport()
    }

    fun retryProfileRefresh() {
        attributionProfileRetryRequired = false
        runtime?.retryProfileRefresh()
    }

    suspend fun collectAttributionProfiles(session: AuthSession, onProfile: (UserProfile) -> Unit) {
        val source = runtime ?: return
        collectAttributionProfileWork(source.reportReceipts, source.profileRetryRevision, source.homeGateRevision) { receipt ->
            if (receipt.epoch != session.epoch) return@collectAttributionProfileWork
            fun isCurrent() = source.currentSessionEpoch() == session.epoch && source.isCurrentReceipt(receipt) &&
                source.reportReceipts.value == receipt
            if (!isCurrent()) return@collectAttributionProfileWork
            try {
                val profile = startupController.refreshAttributionProfile(
                    attempts = BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS,
                    retryIntervalMillis = BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong(),
                    isCurrent = ::isCurrent,
                    profile = repository::userProfileSnapshot,
                )
                if (profile != null && isCurrent()) {
                    attributionProfileRetryRequired = false
                    onProfile(profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) attributionProfileRetryRequired = true
            }
        }
    }

    private fun checkOperation(generation: Long) {
        if (generation != operationGeneration) throw CancellationException("Authentication owner changed")
    }
}

internal suspend fun reconcileAuthenticationFailure(
    error: Exception,
    checkOperation: () -> Unit,
    savedSession: suspend () -> AuthSession?,
): AuthSession? {
    if (error is CancellationException) throw error
    currentCoroutineContext().ensureActive()
    checkOperation()
    val session = try {
        savedSession()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (snapshotFailure: Exception) {
        currentCoroutineContext().ensureActive()
        checkOperation()
        error.addSuppressed(snapshotFailure)
        throw error
    }
    currentCoroutineContext().ensureActive()
    checkOperation()
    return session
}

internal suspend fun <R : Any> collectAttributionProfileWork(
    receipts: Flow<R?>,
    retryRevision: Flow<Long>,
    homeGateRevision: Flow<Long>,
    refresh: suspend (R) -> Unit,
) {
    combine(receipts, retryRevision, homeGateRevision) { receipt, retry, target ->
        Triple(receipt, retry, target)
    }.collectLatest { (receipt, _, _) ->
        if (receipt != null) refresh(receipt)
    }
}
