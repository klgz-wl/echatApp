package yumo.achat.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.Call
import yumo.achat.core.attribution.LoginAttribution

@Serializable
data class ApiResponse<T>(val code: Int, val message: String? = null, val data: T? = null) {
    fun requireData(): T {
        checkSuccess()
        return data ?: throw ServiceFailure.InvalidResponse
    }
    fun checkSuccess() { if (code != 0) throw ServiceFailure.Business(message) }
}
sealed class ServiceFailure : Exception() {
    data object InvalidResponse : ServiceFailure()
    data object SignedOut : ServiceFailure()
    data object Superseded : ServiceFailure()
    data object InvalidAccount : ServiceFailure()
    class Business(val serverMessage: String?) : ServiceFailure()
}
@Serializable
data class LoginRequest(val username: String, val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("package_name") val packageName: String, val platform: String, val version: String, val attribution: LoginAttribution)
@Serializable
data class AnonymousRequest(@SerialName("device_id") val deviceId: String,
    @SerialName("package_name") val packageName: String, val platform: String, val version: String, val attribution: LoginAttribution)
@Serializable
data class GoogleRequest(@SerialName("id_token") val idToken: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("package_name") val packageName: String, val platform: String, val version: String, val attribution: LoginAttribution)
@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)
@Serializable
data class AuthResponse(val token: String, @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("user_id") val userId: String? = null)
interface PublicAuthApi {
    @POST("auth/login") suspend fun login(@Body request: LoginRequest): ApiResponse<AuthResponse>
    @POST("auth/anonymous") suspend fun anonymous(@Body request: AnonymousRequest): ApiResponse<AuthResponse>
    @POST("auth/google") suspend fun google(@Body request: GoogleRequest): ApiResponse<AuthResponse>
    @POST("auth/refresh") fun refresh(@Body request: RefreshRequest): Call<ApiResponse<AuthResponse>>
}
interface AccountApi {
    @POST("auth/logout") suspend fun logout(@retrofit2.http.Tag session: yumo.achat.core.auth.Session): ApiResponse<Unit>
    @POST("auth/delete-account") suspend fun deleteAccount(@retrofit2.http.Tag session: yumo.achat.core.auth.Session): ApiResponse<Unit>
}
