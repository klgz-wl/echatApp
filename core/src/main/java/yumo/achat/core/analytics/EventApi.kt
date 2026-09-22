package yumo.achat.core.analytics

import yumo.achat.core.network.ApiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** 复用参照项目的匿名行为统计接口，不用于内容举报。 */
interface EventApi {
    @POST("events/report")
    suspend fun reportEvent(@Body request: ReportEventRequest): Response<ApiResponse<Unit>>
}

@Serializable
data class ReportEventRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_time") val eventTime: Long,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("package_name") val packageName: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("platform") val platform: String,
    @SerialName("parameters") val parameters: Map<String, String>,
)
