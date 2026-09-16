package yumo.achat.app.data.backend

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import yumo.achat.app.BuildConfig

class AchatBackendClient(
    private val baseUrl: String = BuildConfig.ACHAT_API_BASE_URL,
) {
    fun anonymousLogin(
        deviceId: String,
        packageName: String = BuildConfig.APPLICATION_ID,
        platform: String = "android",
        version: String = BuildConfig.VERSION_NAME,
    ): AuthSession {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("package_name", packageName)
            .put("platform", platform)
            .put("version", version)

        return AchatBackendParsers.parseAuthSession(
            request(
                path = "/api/v1/auth/anonymous",
                method = "POST",
                body = body.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Device-ID" to deviceId,
                ),
            ),
        )
    }

    fun userProfile(token: String): UserProfile =
        AchatBackendParsers.parseUserProfile(get("/api/v1/user/profile", token))

    fun userCurrency(token: String): UserCurrency =
        AchatBackendParsers.parseUserCurrency(get("/api/v1/user/currencies", token))

    fun templates(token: String, modality: String, page: Int = 1, pageSize: Int = 20): List<VisualTemplate> =
        AchatBackendParsers.parseTemplates(
            get("/api/v1/visual-generation/$modality/templates?page=$page&page_size=$pageSize", token),
        )

    private fun get(path: String, token: String): String =
        request(
            path = path,
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $token"),
        )

    private fun request(
        path: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                error(response.ifBlank { "HTTP $responseCode" })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}
