package yumo.achat.app.attribution

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import yumo.achat.app.BuildConfig
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.app.analytics.AdvertisingIdRuntime
import yumo.achat.app.ui.imagevideo.achatBackendConfiguration
import yumo.achat.core.attribution.AttributionConfiguration
import yumo.achat.core.attribution.AttributionCoordinator
import yumo.achat.core.attribution.AttributionStorage
import yumo.achat.core.attribution.AttributionStage
import yumo.achat.core.attribution.AttributionResult
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.ConversionResult
import yumo.achat.core.attribution.LoginAttribution
import yumo.achat.core.attribution.AdvertisingIdProvider
import yumo.achat.core.attribution.readOrNull
import yumo.achat.core.attribution.attributionPayload
import yumo.achat.core.attribution.deviceAttributionReport
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionStorage
import yumo.achat.core.backend.AchatBackendClient
import yumo.achat.core.backend.AchatSessionStore
import yumo.achat.core.backend.AuthSession
import yumo.achat.core.backend.BackendAttribution
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.integration.appsflyer.AppsFlyerAnalytics
import yumo.achat.core.integration.appsflyer.AppsFlyerConfig
import yumo.achat.core.integration.appsflyer.GcdRecheckConfig

internal object AchatAttributionRuntime {
    @Volatile private var instance: AppsFlyerBackendAttribution? = null

    fun get(context: Context): AppsFlyerBackendAttribution =
        instance ?: synchronized(this) {
            instance ?: AppsFlyerBackendAttribution(context.applicationContext).also { instance = it }
        }
}

internal data class HomeGateTarget(val epoch: String?, val resultId: String?)

internal fun shouldActivateCompletion(
    activeUserId: String?,
    completion: StoredAttributionCompletion?,
    result: StoredAttributionResult,
): Boolean = activeUserId != null && completion?.userId == activeUserId &&
    completion.resultId == result.id && completion.source == result.source

internal fun completedLoginAttribution(
    identity: ClientIdentity,
    installDeviceId: String,
): LoginAttribution = attributionPayload(JsonObject(emptyMap()), identity, installDeviceId, "")

internal suspend fun loginAttributionWithGaid(
    installDeviceId: String,
    advertisingIds: AdvertisingIdProvider,
    attribution: suspend () -> LoginAttribution,
): LoginAttribution = coroutineScope {
    val currentId = async { advertisingIds.readOrNull() }
    val existing = attribution()
    val gaid = currentId.await()
    existing.copy(deviceId = gaid ?: installDeviceId, gaid = gaid)
}

internal suspend fun persistCurrentAttributionIfMissing(
    current: ConversionResult,
    hasResult: (AttributionResultSource, JsonObject) -> Boolean,
    persist: suspend (AttributionResult) -> Unit,
) {
    val success = current as? ConversionResult.Success ?: return
    if (!hasResult(success.source, success.data)) {
        persist(AttributionResult(success.source, success.data))
    }
}

internal class AppsFlyerBackendAttribution(
    context: Context,
) : BackendAttribution {
    private val appContext = context.applicationContext
    private val backendConfiguration = achatBackendConfiguration()
    private val identity = ClientIdentity(
        packageName = backendConfiguration.packageName,
        versionName = backendConfiguration.clientVersion,
        timeoutSeconds = 15,
    )
    private val storage = AppAttributionStorage(appContext)
    private val advertisingIds = AdvertisingIdRuntime.get(appContext)
    private val ledgerStore = SharedPreferencesAttributionLedgerStore(
        appContext.getSharedPreferences("achat_attribution", Context.MODE_PRIVATE),
    )
    private fun completionScope(userId: String) = AttributionCompletionScope(
        userId = userId,
        installDeviceId = storage.deviceIdBlocking(),
        packageName = identity.packageName,
        environment = BuildConfig.FLAVOR,
    )
    private val cachedCompletionScope = storage.cachedUserIdBlocking()?.let(::completionScope)
    private val restoredLedger = runCatching { ledgerStore.snapshot() }.getOrNull()
    private val restoredCompletion = cachedCompletionScope?.let { completionScope ->
        restoredLedger?.completionFor(completionScope)
            ?: restoredLedger?.historicalCompletionFor(completionScope)
    }
    private val restoredCompletionNeedsMigration = cachedCompletionScope != null &&
        restoredCompletion != null && restoredLedger?.completionFor(cachedCompletionScope) == null
    private val restoredAttribution = restoredCompletion?.let { completion ->
        runCatching { ledgerStore.snapshot().result(completion.resultId) }.getOrNull()
            ?.let { result -> AttributionResult(result.source, result.data) }
    }
    private val mutableAttributionCompleted = MutableStateFlow(restoredCompletion != null)
    val attributionCompleted = mutableAttributionCompleted.asStateFlow()
    private val source = AppsFlyerAnalytics(
        context = appContext,
        config = AppsFlyerConfig(
            devKey = BuildConfig.APPSFLYER_DEV_KEY,
            debugLogging = BuildConfig.APPSFLYER_DEBUG_LOGGING && BuildConfig.DEBUG,
            enabled = BuildConfig.ENABLE_APPSFLYER,
            diagnosticLogging = BuildConfig.APPSFLYER_DIAGNOSTIC_LOGGING,
            canCollect = { AnalyticsConsent.granted(appContext) },
            gcd = GcdRecheckConfig(
                baseUrl = BuildConfig.APPSFLYER_GCD_BASE_URL.toHttpUrl(),
                timeoutMillis = BuildConfig.APPSFLYER_GCD_TIMEOUT_MS.toLong(),
            ),
            installReferrerTimeoutMillis = BuildConfig.INSTALL_REFERRER_REQUEST_TIMEOUT_MS.toLong(),
            installReferrerStorageName = BuildConfig.INSTALL_REFERRER_STORAGE_NAME,
            installReferrerPaidMediums = BuildConfig.INSTALL_REFERRER_PAID_MEDIUMS.split(',')
                .map(String::trim).filter(String::isNotBlank).toSet(),
            restoredAttribution = restoredAttribution,
            eventQueueCapacity = BuildConfig.ANALYTICS_QUEUE_CAPACITY,
            minSessionIntervalSeconds = BuildConfig.APPSFLYER_MIN_SESSION_INTERVAL_SECONDS,
            requestTimeoutMillis = BuildConfig.APPSFLYER_REQUEST_TIMEOUT_MILLIS.toLong(),
        ),
    )
    private val client = AchatBackendClient(backendConfiguration)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    private val identityMutex = Mutex()
    private val mutableHomeGateRevision = MutableStateFlow(0L)
    val homeGateRevision = mutableHomeGateRevision.asStateFlow()
    private val mutableSnapshots = MutableStateFlow<JsonObject?>(null)
    private val mutableReportReceipts = MutableStateFlow<LedgerReportReceipt?>(null)
    private val mutableStorageFailure = MutableStateFlow<AttributionResult?>(null)
    private val startupNanos = System.nanoTime()
    private val startupId = java.util.UUID.randomUUID().toString()
    private val ownerLock = Any()
    @Volatile private var coordinatorInstance: AttributionCoordinator? = null
    @Volatile private var reporterInstance: LedgerAttributionReportDispatcher? = null

    init {
        if (restoredCompletionNeedsMigration) {
            scope.launch {
                cachedCompletionScope?.let { runCatching { ledgerStore.completion(it) } }
            }
        }
    }

    private fun coordinator(): AttributionCoordinator = coordinatorInstance ?: synchronized(ownerLock) {
        coordinatorInstance ?: AttributionCoordinator(
            source = source,
            storage = storage,
            sessions = storage,
            identity = identity,
            config = AttributionConfiguration(
                timeoutMillis = BuildConfig.ATTRIBUTION_TIMEOUT_MS.toLong(),
                retryAttempts = BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS,
                retryIntervalMillis = BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong(),
                cacheWaitMillis = BuildConfig.ATTRIBUTION_CACHE_WAIT_MS.toLong(),
                loginTimeoutMillis = BuildConfig.ATTRIBUTION_LOGIN_TIMEOUT_MS.toLong(),
                homeTimeoutMillis = BuildConfig.ATTRIBUTION_HOME_TIMEOUT_MS.toLong(),
            ),
        ).also { created ->
            coordinatorInstance = created
            scope.launch {
                created.results.collect(::persistResult)
            }
            scope.launch {
                created.snapshots.collect { mutableSnapshots.value = it }
            }
        }
    }

    private fun reporter(): LedgerAttributionReportDispatcher = reporterInstance ?: synchronized(ownerLock) {
        reporterInstance ?: LedgerAttributionReportDispatcher(
            attempts = BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS.coerceAtLeast(1),
            scope = scope,
            store = ledgerStore,
            startupId = startupId,
            startupNanos = startupNanos,
            retryDelay = { delay(BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong()) },
            report = { session, result, metadata ->
                val payload = deviceAttributionReport(
                    data = result.data,
                    identity = identity,
                    deviceId = storage.deviceIdBlocking(),
                    userId = session.userId,
                    metadata = metadata,
                    gaid = advertisingIds.readOrNull(),
                )
                client.reportAttribution(JSONObject(payload.toString()))
            },
            completionScope = { session -> completionScope(session.userId) },
            onCompletionPersisted = { completion, result ->
                identityMutex.withLock {
                    val activeCompletion = mutableSession.value?.let { active ->
                        runCatching { ledgerStore.completion(completionScope(active.userId)) }.getOrNull()
                    }
                    if (activeCompletion?.reportId == completion.reportId &&
                        shouldActivateCompletion(mutableSession.value?.userId, activeCompletion, result)) {
                        mutableAttributionCompleted.value = true
                        source.stopAttributionAcquisition(AttributionResult(result.source, result.data))
                        mutableHomeGateRevision.value += 1L
                    }
                }
            },
            nowNanos = System::nanoTime,
        ).also { created ->
            reporterInstance = created
            scope.launch {
                created.receipts.collect { mutableReportReceipts.value = it }
            }
        }
    }

    private suspend fun persistResult(result: AttributionResult) {
        var saved = false
        for (attempt in 0 until BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS.coerceAtLeast(1)) {
            try {
                reporter().updateResult(result.source, result.data)
                saved = true
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("Attribution/Storage", "Failed to persist attribution result", error)
                if (attempt + 1 < BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS) {
                    delay(BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong())
                }
            }
        }
        if (saved) {
            if (mutableStorageFailure.value == result) mutableStorageFailure.value = null
            mutableHomeGateRevision.value += 1L
        } else {
            mutableStorageFailure.value = result
        }
    }

    private suspend fun awaitCurrentResultPersisted() {
        val activeReporter = reporter()
        val currentState = source.conversion.value
        persistCurrentAttributionIfMissing(
            current = currentState,
            hasResult = activeReporter::hasResult,
            persist = ::persistResult,
        )
        val current = currentState as? ConversionResult.Success ?: return
        if (activeReporter.hasResult(current.source, current.data)) return
        withTimeout(BuildConfig.ATTRIBUTION_STORAGE_TIMEOUT_MS.toLong()) {
            combine(homeGateRevision, mutableStorageFailure) { _, failure -> failure }
                .first { failure ->
                    if (failure?.source == current.source && failure.data == current.data) {
                        throw AttributionStorageException()
                    }
                    activeReporter.hasResult(current.source, current.data)
                }
        }
    }

    val reportReceipts: StateFlow<LedgerReportReceipt?> get() = mutableReportReceipts.asStateFlow()
    val conversionCallbacks get() = source.conversionCallbacks
    fun isAttributionComplete(): Boolean = mutableAttributionCompleted.value
    fun currentSessionEpoch(): String? = mutableSession.value?.epoch
    fun currentGateTarget(): HomeGateTarget = HomeGateTarget(
        mutableSession.value?.epoch,
        if (isAttributionComplete()) null else reporterInstance?.currentResult()?.id,
    )
    fun isCurrentReceipt(receipt: LedgerReportReceipt): Boolean =
        mutableSession.value?.epoch == receipt.epoch &&
            runCatching { ledgerStore.snapshot().currentResult()?.id }.getOrNull() == receipt.resultId

    suspend fun awaitSplashPreAuthWindow() {
        if (isAttributionComplete()) return
        reporter().updateStage(AttributionStage.SPLASH)
        mutableStorageFailure.value?.let { persistResult(it) }
        coordinator().awaitInitial()
        awaitCurrentResultPersisted()
    }
    suspend fun awaitSplashPostAuthWindow() {
        if (isAttributionComplete()) return
        reporter().updateStage(AttributionStage.POST_LOGIN)
        coordinator().awaitAfterAuth()
        awaitCurrentResultPersisted()
    }
    suspend fun awaitHomeWindow() {
        if (isAttributionComplete()) return
        reporter().updateStage(AttributionStage.HOME)
        coordinator().awaitHome()
        awaitCurrentResultPersisted()
    }

    suspend fun awaitHomeDataGate() {
        if (isAttributionComplete()) return
        val activeReporter = reporter()
        while (true) {
            if (activeReporter.storageFailed()) throw AttributionStorageException()
            val target = activeReporter.currentResult() ?: return
            val session = mutableSession.value
            if (session == null) {
                mutableSession.first { it != null }
                continue
            }
            val selected = combine(homeGateRevision, mutableSession, activeReporter.receipts, activeReporter.exhausted) { _, active, receipt, _ ->
                Triple(activeReporter.currentResult(), active, receipt)
            }.first { (result, active, receipt) ->
                result?.id != target.id || active?.epoch != session.epoch ||
                    (receipt?.epoch == session.epoch && receipt.resultId == target.id) ||
                    activeReporter.isExhausted(session.userId, target.id)
            }
            if (selected.first?.id != target.id || selected.second?.epoch != session.epoch) continue
            if (activeReporter.isExhausted(session.userId, target.id)) throw AttributionReportExhaustedException()
            val receipt = selected.third ?: continue
            receipt.reportedAtNanos?.let { reportedAt ->
                val elapsedNanos = (System.nanoTime() - reportedAt).coerceAtLeast(0L)
                val remainingNanos = (BuildConfig.ATTRIBUTION_HOME_DATA_DELAY_MS.toLong() * 1_000_000L - elapsedNanos).coerceAtLeast(0L)
                if (remainingNanos > 0L) delay((remainingNanos + 999_999L) / 1_000_000L)
            }
            if (activeReporter.currentResult()?.id == target.id && mutableSession.value?.epoch == session.epoch) return
        }
    }

    fun retryHomeReport() {
        if (isAttributionComplete()) return
        mutableStorageFailure.value?.let { failed -> scope.launch { persistResult(failed) } }
        reporter().retryPending()
    }

    fun onActivityResumed(activity: Activity) {
        source.onActivityResumed(activity)
        if (!isAttributionComplete()) reporter().retryPending()
    }

    fun onActivityPaused(activity: Activity) {
        source.onActivityPaused(activity)
    }

    fun onAccessRevoked() {
        source.onAccessRevoked()
    }

    override suspend fun forLogin(installDeviceId: String): LoginAttribution =
        loginAttributionWithGaid(installDeviceId, advertisingIds) {
            if (isAttributionComplete()) {
                completedLoginAttribution(identity, installDeviceId)
            } else runCatching { ledgerStore.snapshot().currentResult() }.getOrNull()?.let { result ->
                attributionPayload(result.data, identity, installDeviceId, source.attributionUid(), result.source)
            } ?: coordinator().forLogin()
        }

    override fun currentId(): String = source.currentId()

    fun analyticsSink(): AnalyticsSink = source

    fun snapshots(): StateFlow<JsonObject?> = mutableSnapshots.asStateFlow()

    override suspend fun report(session: AuthSession) {
        identityMutex.withLock {
            val epochChanged = mutableSession.value?.epoch != session.epoch
            mutableSession.value = session
            val completion = runCatching { ledgerStore.completion(completionScope(session.userId)) }.getOrNull()
            if (completion != null) {
                mutableAttributionCompleted.value = true
                ledgerStore.snapshot().result(completion.resultId)?.let { result ->
                    source.stopAttributionAcquisition(AttributionResult(result.source, result.data))
                }
            } else {
                val wasCompleted = mutableAttributionCompleted.value
                mutableAttributionCompleted.value = false
                if (wasCompleted || restoredCompletion != null) source.resumeAttributionAcquisition()
                reporter().updateSession(session)
            }
            if (epochChanged) mutableHomeGateRevision.value += 1L
        }
    }
}

private class AppAttributionStorage(context: Context) : AttributionStorage, SessionStorage {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("achat_attribution", Context.MODE_PRIVATE)
    private val sessionStore = AchatSessionStore(appContext)

    override suspend fun readAttribution(): JsonObject? =
        preferences.getString(KEY_ATTRIBUTION, null)?.let { Json.parseToJsonElement(it) as? JsonObject }

    override suspend fun writeAttribution(data: JsonObject) {
        preferences.edit().putString(KEY_ATTRIBUTION, data.toString()).apply()
    }

    override suspend fun read(): Session? = null
    override suspend fun write(session: Session?) = Unit
    override suspend fun deviceId(): String = deviceIdBlocking()

    fun deviceIdBlocking(): String = sessionStore.deviceId()
    fun cachedUserIdBlocking(): String? = sessionStore.readSession()?.userId

    private companion object {
        const val KEY_ATTRIBUTION = "appsflyer_install_attribution"
    }
}
