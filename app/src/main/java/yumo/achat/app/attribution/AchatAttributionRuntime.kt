package yumo.achat.app.attribution

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
        ),
    )
    private val client = AchatBackendClient(backendConfiguration)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeSession: AuthSession? = null
    @Volatile private var latestAttribution: JsonObject? = null
    @Volatile private var lastReportedKey: String? = null

    init {
        scope.launch {
            coordinator.snapshots.filterNotNull().collect { data ->
                latestAttribution = data
                reportLatest()
            }
        }
    }

    fun onActivityResumed(activity: Activity) {
        source.onActivityResumed(activity)
    }

    fun onActivityPaused(activity: Activity) {
        source.onActivityPaused(activity)
    }

    override suspend fun forLogin(installDeviceId: String): LoginAttribution =
        coordinator.forLogin()

    override fun currentId(): String = source.currentId()

    fun analyticsSink(): AnalyticsSink = source

    override suspend fun report(session: AuthSession) {
        activeSession = session
        reportLatest()
    }

    private fun reportLatest() {
        val data = latestAttribution ?: return
        val session = activeSession
        val reportKey = "${session?.userId.orEmpty()}:${data}"
        if (lastReportedKey == reportKey) return
        runCatching {
            val payload = deviceAttributionReport(
                data = data,
                identity = identity,
                deviceId = storage.deviceIdBlocking(),
                userId = session?.userId,
            )
            client.reportAttribution(JSONObject(payload.toString()))
            lastReportedKey = reportKey
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

    private companion object {
        const val KEY_ATTRIBUTION = "appsflyer_install_attribution"
    }
}
