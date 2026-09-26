package yumo.achat.core.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import yumo.achat.core.attribution.LoginAttribution

internal interface AchatAuthApi {
    fun loginAnonymously(deviceId: String, attribution: LoginAttribution): AuthSession
    fun refreshAccessToken(refreshToken: String): String
}

class AchatBackendHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException(responseBody.ifBlank { "HTTP $statusCode" })

class AchatBackendClient(
    configuration: AchatBackendConfiguration = AchatBackendConfiguration.Default,
) : AchatAuthApi {
    private val baseUrl: String = configuration.apiBaseUrl
    private val packageName: String = configuration.packageName
    private val clientVersion: String = configuration.clientVersion

    fun anonymousLogin(
        deviceId: String,
        packageName: String = this.packageName,
        platform: String = "android",
        version: String = clientVersion,
        attribution: LoginAttribution = noOpLoginAttribution(deviceId, packageName, version),
    ): AuthSession {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("package_name", packageName)
            .put("platform", platform)
            .put("version", version)
            .put("attribution", attribution.toJsonObject())

        return AchatBackendParsers.parseAuthSession(
            request(
                path = "/api/v1/auth/anonymous",
                method = "POST",
                body = body.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Device-ID" to deviceId,
                    "X-AF-UID" to attribution.afUid,
                ),
            ),
        )
    }

    constructor(baseUrl: String) : this(AchatBackendConfiguration.Default.copy(apiBaseUrl = baseUrl))

    override fun loginAnonymously(deviceId: String, attribution: LoginAttribution): AuthSession =
        anonymousLogin(deviceId = deviceId, attribution = attribution)

    override fun refreshAccessToken(refreshToken: String): String {
        val body = JSONObject().put("refresh_token", refreshToken)
        return AchatBackendParsers.parseRefreshedAccessToken(
            request(
                path = "/api/v1/auth/refresh",
                method = "POST",
                body = body.toString(),
                headers = mapOf("Content-Type" to "application/json"),
            ),
        )
    }

    fun reportAttribution(payload: JSONObject) {
        val response = request(
            path = "/api/v1/attribution/report",
            method = "POST",
            body = payload.toString(),
            headers = mapOf("Content-Type" to "application/json"),
        )
        val envelope = JSONObject(response)
        check(envelope.optInt("code", -1) == 0) {
            envelope.optString("message").takeIf(String::isNotBlank) ?: "Attribution report rejected"
        }
    }

    fun userProfile(token: String): UserProfile =
        AchatBackendParsers.parseUserProfile(get("/api/v1/user/profile", token))

    fun updateUserProfile(
        token: String,
        nickname: String? = null,
        avatarUrl: String? = null,
    ): UserProfile = AchatBackendParsers.parseUserProfile(
        request(
            path = "/api/v1/user/profile",
            method = "PUT",
            body = profileUpdateBody(nickname = nickname, avatarUrl = avatarUrl),
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
            ),
        ),
    )

    fun uploadProfileAvatar(token: String, parts: List<MultipartFormData.Part>): UploadedFile {
        val boundary = "achat-${UUID.randomUUID()}"
        val response = request(
            path = "/api/v1/files/upload",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            ),
            writeBody = { outputStream -> MultipartFormData.write(outputStream, boundary, parts) },
        )
        return AchatBackendParsers.parseUploadedFile(response)
    }

    fun userCurrency(token: String): UserCurrency =
        AchatBackendParsers.parseUserCurrency(get("/api/v1/user/currencies", token))

    fun storeCatalog(token: String): StoreCatalog =
        AchatBackendParsers.parseStoreCatalog(
            get("/api/v1/products?product_type=diamond&platform=android&location=store", token),
        )

    fun createStoreOrder(token: String, productId: String): StoreOrder {
        val body = JSONObject()
            .put("product_id", productId)
            .put("platform", "android")
            .put("trigger", "top_up")
        return AchatBackendParsers.parseStoreOrder(
            request(
                path = "/api/v1/orders",
                method = "POST",
                body = body.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                ),
            ),
        )
    }

    fun initializeStorePayment(token: String, orderId: String): PaymentInitialization {
        val body = JSONObject().put("order_id", orderId)
        return AchatBackendParsers.parsePaymentInitialization(
            request(
                path = "/payment-api/v1/client/payments/initialize",
                method = "POST",
                body = body.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                ),
            ),
        )
    }

    fun storePaymentStatus(token: String, orderId: String): PaymentOrderStatus =
        AchatBackendParsers.parsePaymentOrderStatus(
            get("/payment-api/v1/client/payments/$orderId/status", token),
        )

    fun reportStorePaymentEvent(
        token: String,
        orderId: String,
        eventType: String,
        channelCode: String,
        openMode: String,
        url: String = "",
        errorCode: String = "",
        errorMessage: String = "",
    ) {
        val body = JSONObject()
            .put("event_type", eventType)
            .put("channel_code", channelCode)
            .put("open_mode", openMode)
            .put("page", "top_up")
            .put("url", url)
            .put("error_code", errorCode)
            .put("error_message", errorMessage)
        request(
            path = "/payment-api/v1/client/payments/$orderId/client-events",
            method = "POST",
            body = body.toString(),
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
            ),
        )
    }

    fun templates(
        token: String,
        modality: String,
        sortBy: String,
        categoryId: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): PagedResult<VisualTemplate> {
        require(sortBy == "hot" || sortBy == "latest") { "Unsupported template sort: $sortBy" }
        val query = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            add("sort_by=$sortBy")
            categoryId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("category_id=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}")
            }
        }.joinToString("&")
        return AchatBackendParsers.parseTemplatePage(
            get("/api/v1/visual-generation/$modality/templates?$query", token),
        )
    }

    fun categories(token: String, modality: String): List<VisualCategory> =
        AchatBackendParsers.parseCategories(
            get("/api/v1/visual-generation/$modality/categories", token),
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
    ): PagedResult<VisualResource> {
        val query = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            modality?.takeIf { it.isNotBlank() }?.let { add("modality=$it") }
            resourceType?.takeIf { it.isNotBlank() }?.let { add("resource_type=$it") }
        }.joinToString("&")
        return AchatBackendParsers.parseVisualResourcePage(
            get("/api/v1/visual-generation/resources?$query", token),
        )
    }

    fun walletTransactions(token: String, page: Int = 1, pageSize: Int = 20): List<WalletTransaction> =
        AchatBackendParsers.parseWalletTransactions(
            get("/api/v1/wallet/transactions?page=$page&page_size=$pageSize", token),
        )

    fun createVisualGenerationTask(
        token: String,
        modality: String,
        templateId: String,
        quality: String,
        resourceId: String,
        idempotencyKey: String,
    ): VisualGenerationTask {
        val boundary = "achat-${UUID.randomUUID()}"
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
                throw AchatBackendHttpException(responseCode, response)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

private fun LoginAttribution.toJsonObject(): JSONObject =
    JSONObject()
        .put("attribution_source", attributionSource)
        .put("device_id", deviceId)
        .put("af_uid", afUid)
        .putIfNotNull("network", network)
        .putIfNotNull("campaign", campaign)
        .putIfNotNull("campaign_id", campaignId)
        .putIfNotNull("adgroup", adgroup)
        .putIfNotNull("adgroup_id", adgroupId)
        .putIfNotNull("creative", creative)
        .putIfNotNull("creative_id", creativeId)
        .putIfNotNull("channel", channel)
        .putIfNotNull("country", country)
        .put("platform", platform)
        .put("app_version", appVersion)
        .put("package_name", packageName)
        .put("extra_data", JSONObject(extraData.toString()))

private fun JSONObject.putIfNotNull(name: String, value: String?): JSONObject =
    if (value == null) this else put(name, value)

private fun noOpLoginAttribution(deviceId: String, packageName: String, version: String): LoginAttribution =
    LoginAttribution(
        attributionSource = "appsflyer",
        deviceId = deviceId,
        afUid = "",
        network = null,
        campaign = null,
        campaignId = null,
        adgroup = null,
        adgroupId = null,
        creative = null,
        creativeId = null,
        channel = null,
        country = null,
        platform = "android",
        appVersion = version,
        packageName = packageName,
        extraData = kotlinx.serialization.json.JsonObject(emptyMap()),
    )
