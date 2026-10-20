package yumo.achat.core.integration.appsflyer

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import yumo.achat.core.attribution.hasValidAttribution

data class GcdRecheckConfig(
    val baseUrl: HttpUrl,
    val timeoutMillis: Long,
) {
    init {
        require(baseUrl.scheme == "https" && baseUrl.encodedPath.endsWith('/'))
        require(timeoutMillis > 0)
    }
}

internal fun newGcdHttpClient(config: GcdRecheckConfig): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cache(null)
    .connectTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
    .readTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
    .callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
    .build()

internal class GcdAttributionClient(
    private val config: GcdRecheckConfig,
    private val appId: String,
    private val devKey: String,
    private val calls: Call.Factory = newGcdHttpClient(config),
) {
    init {
        require(appId.isNotBlank() && devKey.isNotBlank())
    }

    suspend fun fetch(uid: String): JsonObject? {
        if (uid.isBlank()) return null
        val url = config.baseUrl.newBuilder()
            .addPathSegment(appId)
            .addQueryParameter("devkey", devKey)
            .addQueryParameter("device_id", uid)
            .build()
        val request = Request.Builder().url(url).get().header("Cache-Control", "no-cache").build()
        val call = calls.newCall(request)
        val callFinished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            try {
                                if (continuation.isActive) continuation.resume(null)
                            } finally {
                                callFinished.complete(Unit)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use {
                                    if (!continuation.isActive || !it.isSuccessful) return@use null
                                    val body = runCatching { it.body?.string() }.getOrNull().orEmpty()
                                    val data = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                                    data?.takeIf(JsonObject::hasValidAttribution)
                                }
                                if (continuation.isActive) continuation.resume(result)
                            } finally {
                                callFinished.complete(Unit)
                            }
                        }
                    })
                } catch (_: Exception) {
                    callFinished.complete(Unit)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        } finally {
            if (call.isCanceled()) withContext(NonCancellable) { callFinished.await() }
        }
    }
}
