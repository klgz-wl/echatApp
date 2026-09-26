package yumo.achat.app.analytics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal object AchatAnalyticsRuntime {
    @Volatile private var instance: AppAnalyticsHub? = null

    fun get(context: Context): AppAnalyticsHub =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): AppAnalyticsHub {
        val consentGranted = AnalyticsConsent.granted(context)
        val backendConfig = achatBackendConfiguration()
        val identity = ClientIdentity(
            packageName = backendConfig.packageName,
            versionName = backendConfig.clientVersion,
            timeoutSeconds = 15,
        )
        val storage = AchatSessionStore(context)
        val policy = AnalyticsPolicy(
            firebase = consentGranted && BuildConfig.ENABLE_FIREBASE_ANALYTICS,
            appsFlyer = consentGranted && BuildConfig.ENABLE_APPSFLYER,
            thinkingData = consentGranted && BuildConfig.ENABLE_THINKINGDATA,
            backend = consentGranted && BuildConfig.ENABLE_BACKEND_ANALYTICS,
        )
        val attributionRuntime = if (policy.appsFlyer) AchatAttributionRuntime.get(context) else null
        var firebaseCampaignSink: FirebaseAnalyticsSink? = null
        val sinks = buildList {
            if (consentGranted && (policy.firebase || BuildConfig.ENABLE_FIREBASE_CRASHLYTICS || BuildConfig.ENABLE_FIREBASE_MESSAGING)) {
                add(
                    FirebaseAnalyticsSink(
                        client = FirebaseSdkClient(
                            context = context,
                            analyticsEnabled = policy.firebase,
                            crashlyticsEnabled = BuildConfig.ENABLE_FIREBASE_CRASHLYTICS,
                            messagingEnabled = BuildConfig.ENABLE_FIREBASE_MESSAGING,
                        ),
                        analyticsEnabled = policy.firebase,
                    ).also { firebaseCampaignSink = it },
                )
            }
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
            // 数数必须先初始化并开启 AppsFlyer 身份分享，再允许 AF init/start。
            attributionRuntime?.let { add(it.analyticsSink()) }
            if (policy.backend) add(
                BackendHttpAnalyticsSink(
                    api = HttpEventApi(backendConfig.apiBaseUrl) {
                        mapOf(
                            "X-Package-Name" to identity.packageName,
                            "X-App-Version" to identity.versionName,
                            "X-Version" to identity.versionName,
                            "X-Platform" to "android",
                            "X-App-Platform" to "android",
                            "X-Device-ID" to storage.deviceId(),
                            "X-AF-UID" to AchatAttributionRuntime.get(context).currentId(),
                        )
                    },
                    identity = identity,
                    deviceId = storage::deviceId,
                    capacity = BuildConfig.ANALYTICS_QUEUE_CAPACITY,
                    store = SharedPreferencesBackendRequestStore(
                        context.getSharedPreferences("${BuildConfig.ANALYTICS_STORAGE_NAME}_backend", Context.MODE_PRIVATE),
                    ),
                ),
            )
        }
        val eventPreferences = context.getSharedPreferences(BuildConfig.ANALYTICS_STORAGE_NAME, Context.MODE_PRIVATE)
        val hub = AppAnalyticsHub(
            queue = BusinessEventQueue(
                capacity = BuildConfig.ANALYTICS_QUEUE_CAPACITY,
                dedupeLimit = BuildConfig.ANALYTICS_DEDUPE_LIMIT,
                dedupeStore = object : EventDedupeStore {
                    override fun read(): List<String> = eventPreferences.getString("dedupe", "")
                        .orEmpty().lineSequence().filter(String::isNotBlank).toList()
                    override fun write(values: List<String>) {
                        eventPreferences.edit().putString("dedupe", values.joinToString("\n")).apply()
                    }
                },
            ),
            sinks = sinks,
            commonParameters = {
                mapOf(
                    "device_id" to storage.deviceId(),
                    "app_version" to identity.versionName,
                    "platform" to "android",
                    "environment" to BuildConfig.FLAVOR,
                )
            },
            attributionSnapshots = attributionRuntime?.snapshots(),
            campaignAttributionSink = firebaseCampaignSink,
        )
        val preferences = eventPreferences
        hub.foregroundAnalytics = ForegroundAnalytics(
            events = hub,
            backgroundTimeoutMs = BuildConfig.ANALYTICS_BACKGROUND_TIMEOUT_MS.toLong(),
            nowElapsed = android.os.SystemClock::elapsedRealtime,
            firstLaunch = {
                val first = !preferences.getBoolean("launched", false)
                if (first) preferences.edit().putBoolean("launched", true).apply()
                first
            },
            newSession = { hub.newSession() },
            schedule = { delayMillis, action ->
                val job: Job = hub.launchDelayed(delayMillis, action)
                val cancel: () -> Unit = { job.cancel() }
                cancel
            },
        )
        return hub
    }
}

internal class AppAnalyticsHub(
    private val queue: BusinessEventQueue,
    private val sinks: List<AnalyticsSink>,
    private val commonParameters: () -> Map<String, Any> = { emptyMap() },
    private val attributionSnapshots: StateFlow<JsonObject?>? = null,
    private val campaignAttributionSink: FirebaseAnalyticsSink? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : EventTracker {
    internal lateinit var foregroundAnalytics: ForegroundAnalytics
    private val initialized = AtomicBoolean(false)
    @Volatile private var activeUserId: String? = null
    private val sinkLocks = sinks.associateWith { Any() }

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        sinks.forEach { runCatching { it.initialize() } }
        scope.launch {
            queue.events.collect { event ->
                val values = analyticsParameters(
                    event.parameters + commonParameters() + mapOf(
                        "event_time" to event.eventTime,
                        "user_id" to event.userId.orEmpty(),
                    ),
                )
                var allAccepted = true
                sinks.forEach { sink ->
                    synchronized(sinkLocks.getValue(sink)) {
                        val result = runCatching {
                            sink.identify(event.userId)
                            sink.event(event.name, values)
                        }
                        allAccepted = allAccepted && result.isSuccess
                        runCatching { sink.identify(activeUserId) }
                    }
                }
                queue.confirm(event, accepted = allAccepted)
            }
        }
        if (attributionSnapshots != null && campaignAttributionSink != null) scope.launch {
            attributionSnapshots.filterNotNull().collect(campaignAttributionSink::recordCampaignAttribution)
        }
    }

    fun identify(userId: String?) {
        activeUserId = userId
        sinks.forEach { sink -> synchronized(sinkLocks.getValue(sink)) { runCatching { sink.identify(userId) } } }
    }

    fun currentUserId(): String? = activeUserId

    fun mode(value: String) {
        queue.mode = value.takeIf { it == "A" || it == "B" } ?: "unknown"
    }

    fun foreground(value: Boolean) {
        initialize()
        if (value) sinks.filterIsInstance<BackendHttpAnalyticsSink>().forEach { it.retryPending() }
        if (::foregroundAnalytics.isInitialized) foregroundAnalytics.foreground(value)
    }

    internal fun newSession() {
        queue.sessionId = java.util.UUID.randomUUID().toString()
    }

    internal fun launchDelayed(delayMillis: Long, action: () -> Unit): Job = scope.launch {
        delay(delayMillis)
        action()
    }

    override fun track(name: String, parameters: Map<String, Any>, userId: String?, onceKey: String?) {
        initialize()
        queue.track(name, parameters, userId ?: activeUserId, onceKey)
    }
}

internal class BackendHttpAnalyticsSink(
    private val api: EventApi,
    private val identity: ClientIdentity,
    private val deviceId: () -> String,
    capacity: Int = 256,
    private val store: BackendRequestStore = MemoryBackendRequestStore(),
    private val retryDelay: suspend () -> Unit = { delay(1_000L) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AnalyticsSink {
    @Volatile private var activeUserId: String? = null
    private val maxPending = capacity
    private val pendingLock = Any()
    private val pending = store.read().toMutableList()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val initialized = AtomicBoolean(false)

    override fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            for (ignored in signals) {
                runCatching { drainPending() }
            }
        }
        if (synchronized(pendingLock) { pending.isNotEmpty() }) signals.trySend(Unit)
    }
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
        synchronized(pendingLock) {
            check(pending.size < maxPending) { "Backend analytics outbox is full" }
            pending += request
            check(store.write(pending.toList())) { "Backend analytics outbox persistence failed" }
        }
        signals.trySend(Unit)
    }

    fun retryPending() { signals.trySend(Unit) }

    private suspend fun drainPending() {
        var remaining = synchronized(pendingLock) { pending.size }
        while (remaining-- > 0) {
            val request = synchronized(pendingLock) { pending.firstOrNull() } ?: return
            var accepted = false
            for (attempt in 0 until 3) {
                accepted = runCatching { api.reportEvent(request) }.getOrNull()
                    ?.let { it.isSuccessful && it.body()?.code == 0 } == true
                if (accepted) break
                if (attempt < 2) retryDelay()
            }
            if (!accepted) {
                synchronized(pendingLock) {
                    if (pending.firstOrNull() == request) {
                        val rotated = pending.drop(1) + request
                        if (store.write(rotated)) {
                            pending.clear()
                            pending.addAll(rotated)
                        }
                    }
                }
                continue
            }
            synchronized(pendingLock) {
                val updated = pending.toMutableList().apply { remove(request) }
                if (store.write(updated)) {
                    pending.clear()
                    pending.addAll(updated)
                }
            }
        }
    }
}

internal interface BackendRequestStore {
    fun read(): List<ReportEventRequest>
    fun write(values: List<ReportEventRequest>): Boolean
}

internal class MemoryBackendRequestStore : BackendRequestStore {
    private var values = emptyList<ReportEventRequest>()
    override fun read(): List<ReportEventRequest> = values
    override fun write(values: List<ReportEventRequest>): Boolean { this.values = values; return true }
}

private class SharedPreferencesBackendRequestStore(
    private val preferences: android.content.SharedPreferences,
) : BackendRequestStore {
    private val serializer = ListSerializer(ReportEventRequest.serializer())
    override fun read(): List<ReportEventRequest> = preferences.getString(KEY, null)?.let {
        runCatching { Json.decodeFromString(serializer, it) }.getOrDefault(emptyList())
    }.orEmpty()
    override fun write(values: List<ReportEventRequest>): Boolean = preferences.edit()
        .putString(KEY, Json.encodeToString(serializer, values))
        .commit()
    private companion object { const val KEY = "pending_requests" }
}

internal class HttpEventApi(
    private val baseUrl: String,
    private val headers: () -> Map<String, String> = { emptyMap() },
) : EventApi {
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
            headers().forEach { (name, value) -> if (value.isNotBlank()) setRequestProperty(name, value) }
            request.userId?.takeIf(String::isNotBlank)?.let { setRequestProperty("X-APP-USER-ID", it) }
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val responseBody = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val businessCode = runCatching { JSONObject(responseBody).optInt("code", -1) }.getOrDefault(-1)
                Response.success(ApiResponse<Unit>(businessCode))
            }
            else Response.error(code, (connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()).toResponseBody())
        } finally {
            connection.disconnect()
        }
    }
}
