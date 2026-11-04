package yumo.achat.core.attribution

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Independent delivery with a cancellable call and an explicit business-success boundary. */
class AttributionHttpClient(
    baseUrl: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build(),
) {
    private val endpoint = baseUrl.trimEnd('/').removeSuffix("/api/v1") + "/api/v1/attribution/report"

    suspend fun report(payload: JsonObject) = suspendCancellableCoroutine<Unit> { continuation ->
        fun value(key: String) = payload[key]?.jsonPrimitive?.content.orEmpty()
        val request = Request.Builder().url(endpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("X-Device-ID", value("device_id"))
            .header("X-APP-USER-ID", value("user_id"))
            .header("X-Package-Name", value("package_name"))
            .header("X-App-Version", value("app_version"))
            .header("X-Version", value("app_version"))
            .header("X-Platform", "android").header("X-App-Platform", "android")
            .apply {
                value("af_uid").takeIf(String::isNotBlank)?.let { header("X-AF-UID", it) }
            }.build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Attribution transport failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val accepted = response.use {
                        it.isSuccessful && runCatching {
                            (Json.parseToJsonElement(it.body?.string().orEmpty()) as? JsonObject)
                                ?.get("code")?.jsonPrimitive?.intOrNull == 0
                        }.getOrDefault(false)
                    }
                    if (continuation.isActive) {
                        if (accepted) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException("Attribution report rejected"))
                    }
                }
            })
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resumeWithException(IOException("Attribution transport unavailable"))
        }
    }
}
