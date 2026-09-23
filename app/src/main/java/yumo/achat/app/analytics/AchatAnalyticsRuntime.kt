package yumo.achat.app.analytics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yumo.achat.app.BuildConfig
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.ui.imagevideo.achatBackendConfiguration
import yumo.achat.core.analytics.AnalyticsPolicy
import yumo.achat.core.analytics.AnalyticsSink
import yumo.achat.core.analytics.EventApi
import yumo.achat.core.analytics.EventTracker
import yumo.achat.core.analytics.ReportEventRequest
import yumo.achat.core.analytics.analyticsParameters
import yumo.achat.core.backend.AchatSessionStore
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.integration.thinkingdata.ThinkingDataAnalytics
import yumo.achat.core.integration.thinkingdata.ThinkingDataConfig
import yumo.achat.core.network.ApiResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.net.HttpURLConnection
import java.net.URL

internal object AchatAnalyticsRuntime {
    @Volatile private var instance: AppAnalyticsHub? = null

    fun get(context: Context): AppAnalyticsHub =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): AppAnalyticsHub {
        val backendConfig = achatBackendConfiguration()
        val identity = ClientIdentity(
            packageName = backendConfig.packageName,
            versionName = backendConfig.clientVersion,
            timeoutSeconds = 15,
        )
        val storage = AchatSessionStore(context)
        val policy = AnalyticsPolicy(
            firebase = BuildConfig.ENABLE_FIREBASE_ANALYTICS,
            appsFlyer = BuildConfig.ENABLE_APPSFLYER,
            thinkingData = BuildConfig.ENABLE_THINKINGDATA,
            backend = BuildConfig.ENABLE_BACKEND_ANALYTICS,
        )
        val sinks = buildList {
            if (policy.appsFlyer) add(AchatAttributionRuntime.get(context).analyticsSink())
            if (policy.thinkingData) add(
                ThinkingDataAnalytics(
                    context = context,
                    config = ThinkingDataConfig(
                        appId = BuildConfig.TD_APP_ID,
                        serverUrl = BuildConfig.TD_SERVER_URL,
                        debugMode = BuildConfig.TD_DEBUG_MODE,
                    ),
                    policy = policy,
                ),
            )
            if (policy.backend) add(
                BackendHttpAnalyticsSink(
                    api = HttpEventApi(backendConfig.apiBaseUrl),
                    identity = identity,
                    deviceId = storage::deviceId,
                ),
            )
        }
        return AppAnalyticsHub(
            queue = BusinessEventQueue(BuildConfig.ANALYTICS_QUEUE_CAPACITY),
            sinks = sinks,
        )
    }
}

internal class AppAnalyticsHub(
    private val queue: BusinessEventQueue,
    private val sinks: List<AnalyticsSink>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : EventTracker {
    @Volatile private var initialized = false
    @Volatile private var activeUserId: String? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        sinks.forEach { runCatching { it.initialize() } }
        scope.launch {
            queue.events.collect { event ->
                val values = analyticsParameters(event.parameters + mapOf("event_time" to event.eventTime))
                sinks.forEach { sink ->
                    runCatching {
                        sink.identify(event.userId)
                        sink.event(event.name, values)
                    }
                    runCatching { sink.identify(activeUserId) }
                }
            }
        }
    }

    fun identify(userId: String?) {
        activeUserId = userId
        sinks.forEach { sink -> runCatching { sink.identify(userId) } }
    }

    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        initialize()
        queue.track(name, parameters, userId ?: activeUserId, onceKey)
    }
}

private class BackendHttpAnalyticsSink(
    private val api: EventApi,
    private val identity: ClientIdentity,
    private val deviceId: () -> String,
) : AnalyticsSink {
    @Volatile private var activeUserId: String? = null

    override fun initialize() = Unit
    override fun identify(userId: String?) {
        activeUserId = userId
    }

    override fun event(name: String, parameters: Map<String, Any>) {
        val request = ReportEventRequest(
            deviceId = deviceId(),
            eventType = yumo.achat.core.analytics.AnalyticsPlatform.BACKEND.eventName(name),
            eventTime = (parameters["event_time"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            userId = activeUserId,
            packageName = identity.packageName,
            appVersion = identity.versionName,
            platform = "android",
            parameters = analyticsParameters(parameters).mapValues { it.value.toString() },
        )
        kotlinx.coroutines.runBlocking { api.reportEvent(request) }
    }
}

private class HttpEventApi(private val baseUrl: String) : EventApi {
    override suspend fun reportEvent(request: ReportEventRequest): Response<ApiResponse<Unit>> {
        val body = JSONObject()
            .put("device_id", request.deviceId)
            .put("event_type", request.eventType)
            .put("event_time", request.eventTime)
            .put("package_name", request.packageName)
            .put("app_version", request.appVersion)
            .put("platform", request.platform)
            .put("parameters", JSONObject(request.parameters))
        request.userId?.let { body.put("user_id", it) }
        val connection = (URL(baseUrl.trimEnd('/') + "/api/v1/events/report").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) Response.success(ApiResponse<Unit>(0))
            else Response.error(code, (connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()).toResponseBody())
        } finally {
            connection.disconnect()
        }
    }
}
