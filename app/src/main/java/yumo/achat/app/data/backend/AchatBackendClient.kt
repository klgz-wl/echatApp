package yumo.achat.app.data.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
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

    fun uploadVisualResource(token: String, imagePart: MultipartFormData.Part): VisualResource {
        val boundary = "achat-${UUID.randomUUID()}"
        val response = request(
            path = "/api/v1/visual-generation/resources",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            ),
            writeBody = { outputStream ->
                MultipartFormData.write(outputStream, boundary, imagePart)
            },
        )
        return AchatBackendParsers.parseVisualResource(response)
    }

    fun visualResources(
        token: String,
        page: Int = 1,
        pageSize: Int = 20,
        modality: String? = null,
        resourceType: String? = null,
    ): List<VisualResource> {
        val query = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            modality?.takeIf { it.isNotBlank() }?.let { add("modality=$it") }
            resourceType?.takeIf { it.isNotBlank() }?.let { add("resource_type=$it") }
        }.joinToString("&")
        return AchatBackendParsers.parseVisualResources(
            get("/api/v1/visual-generation/resources?$query", token),
        )
    }

    fun createVisualGenerationTask(
        token: String,
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
    ): VisualGenerationTask {
        val boundary = "achat-${UUID.randomUUID()}"
        val idempotencyKey = UUID.randomUUID().toString()
        val response = request(
            path = "/api/v1/visual-generation/$modality/tasks",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Idempotency-Key" to idempotencyKey,
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            ),
            writeBody = { outputStream ->
                MultipartFormData.write(
                    outputStream = outputStream,
                    boundary = boundary,
                    parts = listOf(
                        MultipartFormData.textPart("template_id", templateId),
                        MultipartFormData.textPart("quality", quality),
                        MultipartFormData.textPart("idempotency_key", idempotencyKey),
                        MultipartFormData.textPart("resource_id", resourceId),
                    ),
                )
            },
        )
        return AchatBackendParsers.parseVisualGenerationTask(response)
    }

    fun visualGenerationTask(token: String, taskId: String): VisualGenerationTask =
        AchatBackendParsers.parseVisualGenerationTask(
            get("/api/v1/visual-generation/tasks/$taskId", token),
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
        writeBody: ((java.io.OutputStream) -> Unit)? = null,
    ): String {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null || writeBody != null) {
                doOutput = true
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            writeBody?.let { writer ->
                connection.outputStream.use(writer)
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
