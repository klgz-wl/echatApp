package yumo.achat.app.attribution

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import yumo.achat.app.BuildConfig
import yumo.achat.app.ui.imagevideo.achatBackendConfiguration
import yumo.achat.core.attribution.AttributionConfiguration
import yumo.achat.core.attribution.AttributionCoordinator
import yumo.achat.core.attribution.AttributionStorage
import yumo.achat.core.attribution.LoginAttribution
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

internal object AchatAttributionRuntime {
    @Volatile private var instance: AppsFlyerBackendAttribution? = null

    fun get(context: Context): AppsFlyerBackendAttribution =
        instance ?: synchronized(this) {
            instance ?: AppsFlyerBackendAttribution(context.applicationContext).also { instance = it }
        }
}

internal data class HomeGateTarget(val epoch: String?, val snapshot: JsonObject?)

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
    private val source = AppsFlyerAnalytics(
        context = appContext,
        config = AppsFlyerConfig(
            devKey = BuildConfig.APPSFLYER_DEV_KEY,
            debugLogging = BuildConfig.APPSFLYER_DEBUG_LOGGING && BuildConfig.DEBUG,
            enabled = BuildConfig.ENABLE_APPSFLYER,
            diagnosticLogging = BuildConfig.APPSFLYER_DIAGNOSTIC_LOGGING,
            eventQueueCapacity = BuildConfig.ANALYTICS_QUEUE_CAPACITY,
            minSessionIntervalSeconds = BuildConfig.APPSFLYER_MIN_SESSION_INTERVAL_SECONDS,
            requestTimeoutMillis = BuildConfig.APPSFLYER_REQUEST_TIMEOUT_MILLIS.toLong(),
        ),
    )
    private val storage = AppAttributionStorage(appContext)
    private val coordinator = AttributionCoordinator(
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
    )
    private val client = AchatBackendClient(backendConfiguration)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableSuccessfulReports = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val successfulReports = mutableSuccessfulReports.asSharedFlow()
    private val mutableSession = MutableStateFlow<AuthSession?>(null)
    private val mutableHomeGateRevision = MutableStateFlow(0L)
    val homeGateRevision = mutableHomeGateRevision.asStateFlow()
    private val reporter = AttributionReportDispatcher(
        attempts = BuildConfig.ATTRIBUTION_RETRY_ATTEMPTS.coerceAtLeast(1),
        scope = scope,
        retryDelay = { delay(BuildConfig.ATTRIBUTION_RETRY_INTERVAL_MS.toLong()) },
        report = { session, data ->
            val payload = deviceAttributionReport(
                data = data,
                identity = identity,
                deviceId = storage.deviceIdBlocking(),
                userId = session.userId,
            )
            client.reportAttribution(JSONObject(payload.toString()))
        },
        onReported = { session -> mutableSuccessfulReports.tryEmit(session.userId) },
        nowNanos = System::nanoTime,
        deliveryStore = SharedPreferencesAttributionDeliveryStore(
            appContext.getSharedPreferences("achat_attribution", Context.MODE_PRIVATE),
        ),
    )

    init {
        scope.launch {
            coordinator.snapshots.filterNotNull().collect { data ->
                reporter.updateSnapshot(data)
                mutableHomeGateRevision.value += 1L
            }
        }
    }

    val reportReceipts: StateFlow<ReportReceipt?> get() = reporter.receipts
    val conversionCallbacks get() = source.conversionCallbacks
    fun currentSessionEpoch(): String? = mutableSession.value?.epoch
    fun currentGateTarget(): HomeGateTarget = HomeGateTarget(mutableSession.value?.epoch, coordinator.snapshots.value)
    fun isCurrentReceipt(receipt: ReportReceipt): Boolean =
        mutableSession.value?.epoch == receipt.epoch && coordinator.snapshots.value == receipt.snapshot

    suspend fun awaitSplashPreAuthWindow() { coordinator.awaitInitial() }
    suspend fun awaitSplashPostAuthWindow() { coordinator.awaitAfterAuth() }
    suspend fun awaitHomeWindow() { coordinator.awaitHome() }

    suspend fun awaitHomeDataGate() {
        while (true) {
            val snapshot = coordinator.snapshots.value ?: return
            val session = mutableSession.value
            if (session == null) {
                mutableSession.first { it != null }
                continue
            }
            val selected = combine(coordinator.snapshots, mutableSession, reporter.receipts, reporter.exhausted) { data, active, receipt, _ ->
                Triple(data, active, receipt)
            }.first { (data, active, receipt) ->
                data != snapshot || active?.epoch != session.epoch ||
                    (receipt?.epoch == session.epoch && receipt.snapshot == snapshot) ||
                    reporter.isExhausted(session.epoch, snapshot)
            }
            if (selected.first != snapshot || selected.second?.epoch != session.epoch) continue
            if (reporter.isExhausted(session.epoch, snapshot)) throw AttributionReportExhaustedException()
            val receipt = selected.third ?: continue
            val elapsedNanos = (System.nanoTime() - receipt.reportedAtNanos).coerceAtLeast(0L)
            val remainingNanos = (BuildConfig.ATTRIBUTION_HOME_DATA_DELAY_MS.toLong() * 1_000_000L - elapsedNanos).coerceAtLeast(0L)
            if (remainingNanos > 0L) delay((remainingNanos + 999_999L) / 1_000_000L)
            if (coordinator.snapshots.value == snapshot && mutableSession.value?.epoch == session.epoch) return
        }
    }

    fun retryHomeReport() = reporter.retryPending()

    fun onActivityResumed(activity: Activity) {
        source.onActivityResumed(activity)
        reporter.retryPending()
    }

    fun onActivityPaused(activity: Activity) {
        source.onActivityPaused(activity)
    }

    override suspend fun forLogin(installDeviceId: String): LoginAttribution =
        coordinator.forLogin()

    override fun currentId(): String = source.currentId()

    fun analyticsSink(): AnalyticsSink = source

    fun snapshots(): StateFlow<JsonObject?> = coordinator.snapshots

    override suspend fun report(session: AuthSession) {
        val epochChanged = mutableSession.value?.epoch != session.epoch
        mutableSession.value = session
        if (epochChanged) mutableHomeGateRevision.value += 1L
        reporter.updateSession(session)
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

    fun cachedSnapshotBlocking(): JsonObject? = preferences.getString(KEY_ATTRIBUTION, null)
        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private companion object {
        const val KEY_ATTRIBUTION = "appsflyer_install_attribution"
    }
}

private class SharedPreferencesAttributionDeliveryStore(
    private val preferences: android.content.SharedPreferences,
) : AttributionDeliveryStore {
    override fun wasReported(digest: String): Boolean =
        digest in preferences.getStringSet(KEY_REPORTED, emptySet()).orEmpty()

    override fun markReported(digest: String): Boolean {
        val updated = preferences.getStringSet(KEY_REPORTED, emptySet()).orEmpty().toMutableSet()
        updated += digest
        while (updated.size > MAX_REPORTED) updated.remove(updated.first())
        return preferences.edit().putStringSet(KEY_REPORTED, updated).commit()
    }

    private companion object {
        const val KEY_REPORTED = "reported_attribution_digests"
        const val MAX_REPORTED = 32
    }
}
