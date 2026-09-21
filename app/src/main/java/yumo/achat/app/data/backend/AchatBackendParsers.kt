package yumo.achat.app.data.backend

import java.math.BigDecimal
import org.json.JSONObject

object AchatBackendParsers {
    fun parseStoreOrder(json: String): StoreOrder {
        val data = dataObject(json)
        return StoreOrder(
            id = data.getString("order_id"),
            number = data.optNullableString("order_number") ?: "",
            productId = data.getString("product_id"),
            productName = data.optNullableString("product_name") ?: "",
            amount = data.requiredBigDecimal("amount"),
            currency = data.optNullableString("currency") ?: "",
            status = data.optNullableString("status") ?: "",
            createdAt = data.optNullableString("created_at") ?: "",
            paymentUrl = data.optNullableString("payment_url") ?: "",
            obfuscatedAccountId = data.optNullableString("obfuscated_account_id") ?: "",
            obfuscatedProfileId = data.optNullableString("obfuscated_profile_id") ?: "",
        )
    }

    fun parsePaymentInitialization(json: String): PaymentInitialization {
        val data = dataObject(json)
        return PaymentInitialization(
            orderId = data.getString("order_id"),
            channelType = data.optNullableString("channel_type") ?: "",
            channelCode = data.optNullableString("channel_code") ?: "",
            openMode = data.optNullableString("open_mode") ?: "",
            paymentUrl = data.optNullableString("payment_url") ?: "",
            expiresAt = data.optNullableString("expires_at"),
            queryIntervalSeconds = data.optInt("query_interval_seconds", 0),
            maxQuerySeconds = data.optInt("max_query_seconds", 0),
            sdkProductId = data.optJSONObject("sdk_params")?.optNullableString("product_id") ?: "",
        )
    }

    fun parsePaymentOrderStatus(json: String): PaymentOrderStatus {
        val data = dataObject(json)
        return PaymentOrderStatus(
            orderId = data.getString("order_id"),
            status = data.optNullableString("status") ?: "",
            paymentMethod = data.optNullableString("payment_method") ?: "",
            fulfillmentStatus = data.optNullableString("fulfillment_status") ?: "",
            paidAt = data.optNullableString("paid_at"),
            verifiedAt = data.optNullableString("verified_at"),
            fulfilledAt = data.optNullableString("fulfilled_at"),
            thirdPartyPayment = data.optJSONObject("third_party_payment")?.let { payment ->
                ThirdPartyPaymentStatus(
                    channelCode = payment.optNullableString("channel_code") ?: "",
                    status = payment.optNullableString("status") ?: "",
                    openMode = payment.optNullableString("open_mode") ?: "",
                    expiresAt = payment.optNullableString("expires_at"),
                )
            },
        )
    }

    fun parseStoreCatalog(json: String): StoreCatalog {
        val data = dataObject(json)
        val productsJson = data.optJSONArray("products")
        val providersJson = data.optJSONArray("payment_providers")
        val products = buildList {
            if (productsJson != null) {
                for (index in 0 until productsJson.length()) {
                    val item = productsJson.getJSONObject(index)
                    add(
                        StoreProduct(
                            id = item.getString("id"),
                            name = item.optNullableString("name") ?: "Diamond pack",
                            description = item.optNullableString("description") ?: "",
                            type = item.optNullableString("type") ?: "",
                            value = item.optInt("value", 0),
                            bonusValue = item.optInt("bonus_value", 0),
                            firstBuyBonusValue = item.optInt("first_buy_bonus_value", 0),
                            currency = item.optNullableString("currency") ?: "",
                            originalPrice = item.optBigDecimal("original_price"),
                            price = item.requiredBigDecimal("price"),
                            firstBuyPrice = item.optBigDecimal("first_buy_price"),
                            discountRate = item.optBigDecimal("discount_rate"),
                            firstBuyDiscount = item.optBigDecimal("first_buy_discount"),
                            icon = item.optNullableString("icon") ?: "",
                            isFirstBuyPromotion = item.optBoolean("is_first_buy_promotion", false),
                            isPromotion = item.optBoolean("is_promotion", false),
                            isSubscription = item.optBoolean("is_subscription", false),
                            promotionType = item.optNullableString("promotion_type") ?: "",
                            sortOrder = item.optInt("sort_order", 0),
                            tags = item.optNullableString("tags") ?: "",
                            thirdPartyProductId = item.optNullableString("third_party_product_id") ?: "",
                            vipLevel = item.optInt("vip_level", 0),
                        ),
                    )
                }
            }
        }.sortedWith(compareBy<StoreProduct> { it.sortOrder }.thenBy { it.id })
        val providers = buildList {
            if (providersJson != null) {
                for (index in 0 until providersJson.length()) {
                    val item = providersJson.getJSONObject(index)
                    val platforms = item.optJSONArray("supported_platforms")
                    add(
                        StorePaymentProvider(
                            priority = item.optInt("priority", 0),
                            code = item.optNullableString("provider_code") ?: "",
                            name = item.optNullableString("provider_name") ?: "",
                            supportedPlatforms = buildList {
                                if (platforms != null) {
                                    for (platformIndex in 0 until platforms.length()) {
                                        add(platforms.optString(platformIndex))
                                    }
                                }
                            }.filter { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.sortedWith(compareByDescending<StorePaymentProvider> { it.priority }.thenBy { it.code })
        val userInfo = data.optJSONObject("user_info")?.let { item ->
            StoreUserInfo(
                currentDiamond = item.optInt("current_diamond", 0),
                hasMadeFirstPurchase = item.optBoolean("has_made_first_purchase", false),
                isVip = item.optBoolean("is_vip", false),
            )
        }
        return StoreCatalog(
            products = products,
            paymentProviders = providers,
            userInfo = userInfo,
        )
    }

    fun parseAuthSession(json: String): AuthSession {
        val data = dataObject(json)
        return AuthSession(
            userId = data.getString("user_id"),
            token = data.getString("token"),
            refreshToken = data.getString("refresh_token"),
            sessionId = data.optNullableString("session_id") ?: "",
            isAnonymous = data.optBoolean("is_anonymous", true),
        )
    }

    fun parseUserProfile(json: String): UserProfile {
        val data = dataObject(json)
        return UserProfile(
            id = data.getString("id"),
            username = data.optNullableString("username"),
            nickname = data.optNullableString("nickname"),
            name = data.optNullableString("name"),
            avatarUrl = data.optNullableString("avatar"),
            largeAvatarUrl = data.optNullableString("avatar_large"),
        )
    }

    fun parseUploadedFile(json: String): UploadedFile {
        val data = dataObject(json)
        return UploadedFile(
            id = data.getString("id"),
            fileUrl = data.optNullableString("file_url") ?: error("Missing uploaded file URL"),
            storagePath = data.optNullableString("storage_path") ?: "",
            mimeType = data.optNullableString("mime_type") ?: "",
        )
    }

    fun parseUserCurrency(json: String): UserCurrency {
        val data = dataObject(json)
        return UserCurrency(
            userId = data.getString("user_id"),
            diamondBalance = data.optInt("diamond_balance", 0),
            diamondEarned = data.optInt("diamond_earned", 0),
            diamondSpent = data.optInt("diamond_spent", 0),
        )
    }

    fun parseTemplates(json: String): List<VisualTemplate> {
        val data = dataObject(json)
        val items = data.opt("items") as? org.json.JSONArray ?: error("Missing or invalid template items")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val prices = item.optJSONObject("prices")
                add(
                    VisualTemplate(
                        id = item.getString("id"),
                        categoryId = item.optNullableString("category_id"),
                        categoryName = item.optNullableString("category_name"),
                        name = item.optNullableString("name") ?: "Visual Template",
                        fileUrl = item.optNullableString("file_url") ?: "",
                        mimeType = item.optNullableString("mime_type") ?: "",
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        durationSeconds = item.optDouble("duration", 0.0).toInt(),
                        hotScore = item.optDouble("hot_score", 0.0).toInt(),
                        fastPrice = prices?.optNullableInt("fast"),
                        qualityPrice = prices?.optNullableInt("quality"),
                    ),
                )
            }
        }.sortedByDescending { it.hotScore }
    }

    fun parseCategories(json: String): List<VisualCategory> {
        val data = dataArray(json)
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                add(
                    VisualCategory(
                        id = item.getString("id"),
                        name = item.optNullableString("name") ?: "Category",
                        sortOrder = item.optInt("sort_order", 0),
                    ),
                )
            }
        }.sortedBy { it.sortOrder }
    }

    fun parseVisualResource(json: String): VisualResource {
        val data = dataObject(json)
        return parseVisualResourceObject(data)
    }

    fun parseVisualResources(json: String): List<VisualResource> {
        val data = dataObject(json)
        val items = data.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                add(parseVisualResourceObject(items.getJSONObject(index)))
            }
        }
    }

    fun parseVisualGenerationTask(json: String): VisualGenerationTask {
        val data = dataObject(json)
        return VisualGenerationTask(
            taskId = data.getString("task_id"),
            status = data.optNullableString("status") ?: "",
            modality = data.optNullableString("modality") ?: "",
            quality = data.optNullableString("quality") ?: "",
            templateId = data.optNullableString("template_id") ?: "",
            diamondCost = data.optInt("diamond_cost", 0),
            estimatedPollIntervalSeconds = data.optNullableInt("estimated_poll_interval_seconds"),
            refunded = data.optBoolean("refunded", false),
            errorMessage = data.optNullableString("error_message"),
            resource = data.optJSONObject("resource")?.let(::parseTaskResourceObject),
        )
    }

    private fun parseVisualResourceObject(data: JSONObject): VisualResource {
        return VisualResource(
            id = data.getString("id"),
            taskId = data.optNullableString("task_id"),
            resourceType = data.optNullableString("resource_type") ?: "",
            modality = data.optNullableString("modality") ?: "",
            templateId = data.optNullableString("template_id"),
            templateName = data.optNullableString("template_name"),
            url = data.optNullableString("url") ?: "",
            thumbnailUrl = data.optNullableString("thumbnail_url"),
            mimeType = data.optNullableString("mime_type") ?: "",
            width = data.optInt("width", 0),
            height = data.optInt("height", 0),
            durationSeconds = data.optDouble("duration", 0.0).toInt(),
            createdAt = data.optNullableString("created_at"),
            expiresAt = data.optNullableString("expires_at"),
        )
    }

    private fun parseTaskResourceObject(data: JSONObject): VisualResource =
        VisualResource(
            id = data.optNullableString("id") ?: "",
            taskId = null,
            resourceType = "generated",
            modality = "",
            url = data.optNullableString("url") ?: "",
            thumbnailUrl = null,
            mimeType = data.optNullableString("mime_type") ?: "",
            width = data.optInt("width", 0),
            height = data.optInt("height", 0),
            durationSeconds = data.optDouble("duration", 0.0).toInt(),
        )

    private fun dataObject(json: String): JSONObject {
        val root = JSONObject(json)
        val code = root.optInt("code", 0)
        if (code != 0 && code != 200) {
            error(root.optString("message", "Backend request failed"))
        }
        return root.optJSONObject("data") ?: error(root.optString("message", "Missing response data"))
    }

    private fun dataArray(json: String): org.json.JSONArray {
        val root = JSONObject(json)
        val code = root.optInt("code", 0)
        if (code != 0 && code != 200) {
            error(root.optString("message", "Backend request failed"))
        }
        return root.optJSONArray("data") ?: error(root.optString("message", "Missing response data"))
    }
}

private fun JSONObject.optBigDecimal(name: String): BigDecimal {
    val value = opt(name)
    if (value == null || value == JSONObject.NULL) return BigDecimal.ZERO
    return value.toString().toBigDecimalOrNull() ?: BigDecimal.ZERO
}

private fun JSONObject.requiredBigDecimal(name: String): BigDecimal {
    val value = opt(name)
    check(value != null && value != JSONObject.NULL) { "Missing $name" }
    return value.toString().toBigDecimalOrNull() ?: error("Invalid $name")
}

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
