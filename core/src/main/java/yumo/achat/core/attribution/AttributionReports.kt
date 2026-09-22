package yumo.achat.core.attribution

import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.auth.SessionStorage
import yumo.achat.core.config.ClientIdentity
import yumo.achat.core.network.ApiResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import retrofit2.http.Body
import retrofit2.http.POST
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 与参考工程相同的公开设备归因补报接口，不重建登录会话。 */
interface AttributionReportApi {
    @POST("attribution/report") suspend fun report(@Body payload: JsonObject): ApiResponse<Unit>
}

fun deviceAttributionReport(data: JsonObject, identity: ClientIdentity, deviceId: String, userId: String?): JsonObject {
    val attribution = attributionPayload(data, identity, deviceId, "")
    return buildJsonObject {
        put("device_id", deviceId); put("package_name", identity.packageName)
        put("platform", "android"); put("app_version", identity.versionName)
        put("attribution_source", "AppsFlyer")
        userId?.let { put("user_id", it) }
        attribution.network?.let { put("network", it) }
        attribution.campaign?.let { put("campaign", it) }
        attribution.campaignId?.let { put("campaign_id", it) }
        attribution.adgroup?.let { put("adgroup", it) }
        attribution.adgroupId?.let { put("adgroup_id", it) }
        attribution.creative?.let { put("creative", it) }
        attribution.creativeId?.let { put("creative_id", it) }
        attribution.country?.let { put("country", it) }
        data["click_time"]?.let { put("click_time", it) }
        data["install_time"]?.let { put("install_time", it) }
        put("extra_data", data)
    }
}

@Singleton
class AttributionReports @Inject constructor(private val coordinator: AttributionCoordinator, private val api: AttributionReportApi,
    private val sessions: SessionCoordinator, private val storage: SessionStorage, private val identity: ClientIdentity,
    private val config: AttributionConfiguration, private val profiles: yumo.achat.core.auth.UserProfileRepository) {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(coordinator.snapshots, sessions.state.map { it?.epoch }.distinctUntilChanged()) { data, epoch -> data to epoch }
                .distinctUntilChanged().collectLatest { (data, epoch) ->
                    if (data == null) return@collectLatest
                    var reported = false
                    repeat(config.retryAttempts) { attempt ->
                        try {
                            val deviceId = storage.deviceId()
                            val session = sessions.current
                            if (session?.epoch != epoch) return@collectLatest
                            if (!reported) {
                                api.report(deviceAttributionReport(data, identity, deviceId, session?.userId)).checkSuccess()
                                reported = true
                            }
                            // 补报完成后重新读服务端标记，避免归因到达时抢先读取旧值。
                            if (epoch != null) profiles.refresh(epoch)
                            return@collectLatest
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { Timber.tag("AppsFlyer/Attribution").w("归因补报或资料刷新未成功，保留真实回调重试") }
                        if (attempt + 1 < config.retryAttempts) delay(config.retryIntervalMillis)
                    }
                }
        }
    }
}
