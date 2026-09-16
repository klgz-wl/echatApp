package yumo.achat.app.data.backend

import org.json.JSONObject

object AchatBackendParsers {
    fun parseAuthSession(json: String): AuthSession {
        val data = dataObject(json)
        return AuthSession(
            userId = data.getString("user_id"),
            token = data.getString("token"),
            refreshToken = data.getString("refresh_token"),
            sessionId = data.getString("session_id"),
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
        val items = data.optJSONArray("items") ?: return emptyList()
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

    fun parseVisualResource(json: String): VisualResource {
        val data = dataObject(json)
        return parseVisualResourceObject(data)
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
            url = data.optNullableString("url") ?: "",
            thumbnailUrl = data.optNullableString("thumbnail_url"),
            mimeType = data.optNullableString("mime_type") ?: "",
            width = data.optInt("width", 0),
            height = data.optInt("height", 0),
            durationSeconds = data.optDouble("duration", 0.0).toInt(),
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
}

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
