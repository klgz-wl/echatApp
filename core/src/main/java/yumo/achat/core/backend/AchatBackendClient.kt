package yumo.achat.core.backend

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import yumo.achat.core.attribution.LoginAttribution
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface AchatAuthApi {
    fun loginAnonymously(deviceId: String, attribution: LoginAttribution): AuthSession
    fun register(username: String, password: String, deviceId: String, attribution: LoginAttribution): AuthSession
    fun refreshAccessToken(refreshToken: String): String
    suspend fun refreshAccessTokenCancellable(refreshToken: String): String = refreshAccessToken(refreshToken)
    fun logout(token: String)
    fun deleteAccount(token: String)
}

open class AchatBackendHttpException(
    val statusCode: Int,
    val responseBody: String,
) : IllegalStateException(responseBody.ifBlank { "HTTP $statusCode" })

class AchatBackendClient(
    configuration: AchatBackendConfiguration = AchatBackendConfiguration.Default,
) : AchatAuthApi {
    private val baseUrl: String = configuration.apiBaseUrl.trimEnd('/').removeSuffix("/api/v1")
    private val packageName: String = configuration.packageName
    private val clientVersion: String = configuration.clientVersion
    private val cancellableRequests by lazy {
        OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
    }

    // OkHttp can follow up a 503 with Retry-After: 0 even when connection retries are disabled.
    // Playback grants have no idempotency key, so surface that first response for a user retry.
    private val homeRequests by lazy {
        cancellableRequests.newBuilder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }.build()
    }

    fun clientConfigs(keys: Collection<String>, token: String? = null, deviceId: String? = null): ClientConfigs {
        val requestedKeys = ClientConfigKeys.merged(keys)
        val query = "package_name=${encodeQuery(packageName)}&platform=android&keys=${encodeQuery(requestedKeys.joinToString(","))}"
        val headers = buildMap {
            put("X-Package-Name", packageName)
            put("X-Platform", "android")
            put("X-App-Platform", "android")
            put("X-App-Version", clientVersion)
            put("X-Version", clientVersion)
            deviceId?.takeIf(String::isNotBlank)?.let { put("X-Device-ID", it) }
            token?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
        }
        return parseClientConfigs(request("/api/v1/client/configs?$query", "GET", headers = headers), packageName, "android")
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

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
        attribution.gaid?.let { body.put("gaid", it) }

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

    override fun register(username: String, password: String, deviceId: String, attribution: LoginAttribution): AuthSession {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device_id", deviceId)
            .put("package_name", packageName)
            .put("platform", "android")
            .put("version", clientVersion)
            .put("attribution", attribution.toJsonObject())
        attribution.gaid?.let { body.put("gaid", it) }
        return AchatBackendParsers.parseAuthSession(request(
            path = "/api/v1/auth/register", method = "POST", body = body.toString(),
            headers = mapOf("Content-Type" to "application/json", "X-Device-ID" to deviceId, "X-AF-UID" to attribution.afUid),
        ))
    }

    override fun logout(token: String) = postAuthenticatedAuthAction("/api/v1/auth/logout", token)

    override fun deleteAccount(token: String) = postAuthenticatedAuthAction("/api/v1/auth/delete-account", token)

    private fun postAuthenticatedAuthAction(path: String, token: String) {
        val response = request(path, "POST", headers = mapOf("Authorization" to "Bearer $token"))
        val envelope = JSONObject(response)
        check(envelope.optInt("code", -1) == 0) {
            envelope.optString("message").takeIf(String::isNotBlank) ?: "Authentication action rejected"
        }
    }

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

    override suspend fun refreshAccessTokenCancellable(refreshToken: String): String {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val request = Request.Builder().url(baseUrl + "/api/v1/auth/refresh")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("X-Package-Name", packageName).header("X-App-Version", clientVersion).build()
        return cancellableRequest(request, AchatBackendParsers::parseRefreshedAccessToken)
    }

    fun reportAttribution(payload: JSONObject) {
        val afUid = payload.optString("af_uid").takeIf(String::isNotBlank)
            ?: payload.optJSONObject("extra_data")?.optString("af_uid")?.takeIf(String::isNotBlank)
            ?: ""
        val deviceId = payload.optString("device_id").takeIf(String::isNotBlank).orEmpty()
        val userId = payload.optString("user_id").takeIf(String::isNotBlank).orEmpty()
        val response = request(
            path = "/api/v1/attribution/report",
            method = "POST",
            body = payload.toString(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-AF-UID" to afUid,
                "X-Device-ID" to deviceId,
                "X-APP-USER-ID" to userId,
            ),
        )
        val envelope = JSONObject(response)
        check(envelope.optInt("code", -1) == 0) {
            envelope.optString("message").takeIf(String::isNotBlank) ?: "Attribution report rejected"
        }
    }

    fun userProfile(token: String): UserProfile =
        AchatBackendParsers.parseUserProfile(get("/api/v1/user/profile", token))

    suspend fun userProfileCancellable(token: String): UserProfile {
        val request = Request.Builder().url(baseUrl + "/api/v1/user/profile")
            .header("Authorization", "Bearer $token")
            .header("X-Package-Name", packageName).header("X-App-Version", clientVersion).build()
        return cancellableRequest(request, AchatBackendParsers::parseUserProfile)
    }

    private suspend fun <T> cancellableRequest(request: Request, parse: (String) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = cancellableRequests.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw AchatBackendHttpException(it.code, body)
                            parse(body)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

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

    fun vipInfo(token: String): VipInfo = AchatBackendParsers.parseVipInfo(get("/api/v1/vip/info", token))

    fun membershipCatalog(token: String): StoreCatalog =
        AchatBackendParsers.parseStoreCatalog(
            get("/api/v1/products?product_type=membership&platform=android&location=store", token),
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
        homeFeatured: Boolean = false,
    ): PagedResult<VisualTemplate> {
        require(sortBy == "hot" || sortBy == "latest") { "Unsupported template sort: $sortBy" }
        val query = buildList {
            add("page=$page")
            add("page_size=$pageSize")
            add("sort_by=$sortBy")
            add("is_vip=1")
            if (modality == "video") add("home_featured=$homeFeatured")
            categoryId?.trim()?.takeIf { it.isNotEmpty() && !(modality == "video" && homeFeatured) }?.let {
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
        catalogContext: HomeCatalogContext? = null,
    ): VisualGenerationTask {
        val boundary = "achat-${UUID.randomUUID()}"
        val response = try {
            request(
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
                        parts = buildList {
                            addAll(listOf(
                            MultipartFormData.textPart("template_id", templateId),
                            MultipartFormData.textPart("quality", quality),
                            MultipartFormData.textPart("idempotency_key", idempotencyKey),
                            MultipartFormData.textPart("resource_id", resourceId),
                            ))
                            catalogContext?.let {
                                add(MultipartFormData.textPart("catalog_id", it.catalogId))
                                add(MultipartFormData.textPart("catalog_item_id", it.catalogItemId))
                                add(MultipartFormData.textPart("catalog_binding_revision", it.catalogBindingRevision.toString()))
                            }
                        },
                    )
                },
            )
        } catch (error: AchatBackendHttpException) {
            // Keep the HTTP shell so authenticated() can still recover from 401.
            AchatBackendParsers.generationCreationRejection(error.responseBody)?.let { error.initCause(it) }
            throw error
        }
        return AchatBackendParsers.parseVisualGenerationCreation(response)
    }

    fun visualGenerationTask(token: String, taskId: String): VisualGenerationTask =
        AchatBackendParsers.parseVisualGenerationTask(
            get("/api/v1/visual-generation/tasks/$taskId", token),
        )

    fun homeFeed(token: String, requestId: String): HomeFeedBatch {
        require(requestId.isNotBlank() && requestId.length <= 128)
        return parseHomeResponse { HomeFeedParsers.batch(homeRequest(token, "/api/v1/resource-catalog/home-feed?request_id=${encodeQuery(requestId)}&limit=20&is_vip=1")) }
    }

    fun acknowledgeHomeFeed(token: String, batchId: String, displayedThroughIndex: Int, idempotencyKey: String) {
        require(batchId.isNotBlank() && displayedThroughIndex >= 0 && idempotencyKey.isNotBlank() && idempotencyKey.length <= 128)
        val confirmation = homeRequest(token, "/api/v1/resource-catalog/home-feed/${encodeQuery(batchId)}/ack", JSONObject()
            .put("displayed_through_index", displayedThroughIndex).put("idempotency_key", idempotencyKey).toString())
        if (confirmation.optString("status") != "accepted") throw HomeFeedServiceException("ACK_NOT_ACCEPTED")
    }

    fun shortDramas(token: String, page: Int = 1, pageSize: Int = 20, sort: String = "hot", tag: String = ""): DramaPage<ShortDramaSummary> {
        require(page >= 1 && pageSize in 1..50 && sort in setOf("hot", "latest"))
        val path = "/api/v1/short-dramas?page=$page&page_size=$pageSize&sort=$sort&is_home=true&is_vip=true" +
            tag.trim().takeIf { it.isNotEmpty() }?.let { "&tag=${encodeQuery(it)}" }.orEmpty()
        return parseHomeResponse { HomeFeedParsers.page(homeRequest(token, path), HomeFeedParsers::summary) }
    }
    fun shortDramaHistory(token: String, page: Int = 1, pageSize: Int = 20): DramaPage<DramaHistoryEntry> {
        require(page >= 1 && pageSize in 1..50)
        return parseHomeResponse { HomeFeedParsers.page(homeRequest(token, "/api/v1/short-dramas/history?page=$page&page_size=$pageSize"), HomeFeedParsers::history) }
    }
    fun shortDramaAds(token: String): List<ShortDramaAd> = parseHomeResponse {
        HomeFeedParsers.ads(homeRequest(token, "/api/v1/short-drama-ads"))
    }

    fun shortDramaDetail(token: String, dramaId: String): ShortDramaDetail {
        require(dramaId.isNotBlank())
        return parseHomeResponse { HomeFeedParsers.drama(homeRequest(token, "/api/v1/short-dramas/${encodeQuery(dramaId)}")) }
    }

    fun playDramaEpisode(token: String, dramaId: String, episodeId: String): DramaPlaybackGrant {
        require(dramaId.isNotBlank() && episodeId.isNotBlank())
        return parseHomeResponse { HomeFeedParsers.playback(homeRequest(token, "/api/v1/short-dramas/${encodeQuery(dramaId)}/episodes/${encodeQuery(episodeId)}/play", "{}")) }
    }

    fun refreshDramaGrant(token: String, grant: String): DramaPlaybackGrant {
        require(grant.isNotBlank() && grant != "." && grant != "..")
        return parseHomeResponse { HomeFeedParsers.playback(homeRequest(token, "/api/v1/short-dramas/media/${encodeQuery(grant)}/refresh", "{}")) }
    }

    fun unlockDramaEpisode(token: String, dramaId: String, episodeId: String, confirmedAmount: Int, idempotencyKey: String) {
        require(dramaId.isNotBlank() && episodeId.isNotBlank() && confirmedAmount >= 0 && idempotencyKey.isNotBlank() && idempotencyKey.length <= 200)
        val confirmation = homeRequest(token, "/api/v1/short-dramas/${encodeQuery(dramaId)}/episodes/${encodeQuery(episodeId)}/unlock", JSONObject()
            .put("payment_idempotency_key", idempotencyKey).put("confirmed_amount", confirmedAmount).put("currency", "coins").toString())
        if (confirmation.opt("is_unlocked") != true) throw HomeFeedServiceException("UNLOCK_NOT_CONFIRMED")
    }

    fun resolveDramaPreview(token: String, resolverUrl: String): ResolvedDramaPreview = resolveDramaResource(token, resolverUrl, "preview")
    fun resolveDramaMedia(token: String, resolverUrl: String): ResolvedDramaPreview = resolveDramaResource(token, resolverUrl, "full")
    fun resolveDramaSubtitle(token: String, resolverUrl: String): ResolvedDramaPreview = resolveDramaResource(token, resolverUrl, "subtitle")

    private fun resolveDramaResource(token: String, resolverUrl: String, kind: String): ResolvedDramaPreview {
        val base = java.net.URI(baseUrl)
        val uri = try { base.resolve(java.net.URI(resolverUrl)) } catch (_: Exception) { throw HomeFeedServiceException("INVALID_DRAMA_RESOLVER") }
        val basePort = if (base.port >= 0) base.port else if (base.scheme == "https") 443 else 80
        val uriPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        val segment = "[A-Za-z0-9_-][A-Za-z0-9_.-]*"
        val pathPattern = when (kind) {
            "preview" -> "/api/v1/short-dramas/previews/$segment"
            "subtitle" -> "/api/v1/short-dramas/media/$segment/subtitles/$segment"
            else -> "/api/v1/short-dramas/media/$segment"
        }
        if (uri.scheme != base.scheme || uri.host != base.host || uriPort != basePort || uri.userInfo != null || uri.fragment != null ||
            !Regex(pathPattern).matches(uri.rawPath.orEmpty())) throw HomeFeedServiceException("INVALID_DRAMA_RESOLVER")
        return parseHomeResponse {
            val data = homeRequest(token, uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: ""))
            val expectedGrant = if (kind == "preview") "preview" else "full"
            if (data.has("grant_type") && data.optString("grant_type") != expectedGrant) throw HomeFeedServiceException("INVALID_DRAMA_GRANT")
            val mediaUrl = data.getString("url")
            val media = java.net.URI(mediaUrl)
            if (media.scheme?.lowercase() !in setOf("http", "https") || media.host.isNullOrBlank() || media.userInfo != null) throw HomeFeedServiceException("INVALID_DRAMA_URL")
            ResolvedDramaPreview(mediaUrl, data.getString("expires_at"))
        }
    }

    private inline fun <T> parseHomeResponse(parse: () -> T): T = try { parse() } catch (e: HomeFeedServiceException) {
        throw e
    } catch (_: Exception) { throw HomeFeedServiceException("INVALID_HOME_RESPONSE") }

    private fun homeRequest(token: String, path: String, body: String? = null): JSONObject {
        val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token")
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        try {
            homeRequests.newCall(request).execute().use { response ->
                val status = response.code
                val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                val rawCode = root?.opt("code")
                val successCode = rawCode == null || rawCode == JSONObject.NULL || rawCode == 0 || rawCode == "0"
                if (!response.isSuccessful || !successCode) {
                    val safeCode = (rawCode as? String)?.takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) } ?: "HTTP_$status"
                    throw HomeFeedServiceException(safeCode, status)
                }
                return root?.optJSONObject("data") ?: throw HomeFeedServiceException("INVALID_HOME_RESPONSE", status)
            }
        } catch (e: HomeFeedServiceException) { throw e
        } catch (_: IOException) { throw HomeFeedServiceException("HOME_NETWORK_ERROR") }
    }

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
        .putIfNotNull("gaid", gaid)

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
