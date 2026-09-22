package yumo.achat.core.region

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** 独立客户端不携带业务认证、设备标识或日志拦截器；显式读取 JSON 字段，无反射模型。 */
class CountryIsRegionSource(url: String, timeoutMillis: Long) : IpRegionSource {
    private val request = Request.Builder().url(url).get().build().also { require(it.url.isHttps) }
    private val client = OkHttpClient.Builder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun country(): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { readCountryResponse(response) }
                continuation.resumeWith(result)
            }
        })
    }
}

internal fun readCountryResponse(response: Response): String = response.use {
    if (!it.isSuccessful) throw RegionUnavailableException("R205-${it.code}")
    parseCountry(it.body?.string().orEmpty())
}

internal fun parseCountry(body: String): String {
    val json = try { Json.parseToJsonElement(body) }
        catch (_: IllegalArgumentException) { throw RegionUnavailableException("R206") }
    val country = (json as? JsonObject)?.get("country") as? JsonPrimitive
    if (country?.isString != true) throw RegionUnavailableException("R206")
    return country.content
}
