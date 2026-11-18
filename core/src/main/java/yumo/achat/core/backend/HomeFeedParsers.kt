package yumo.achat.core.backend

import org.json.JSONObject

internal object HomeFeedParsers {
    private fun JSONObject.nonNegativeInt(key: String): Int {
        val value = get(key)
        require(value is Number)
        return java.math.BigDecimal(value.toString()).intValueExact().also { require(it >= 0) }
    }
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else nonNegativeInt(key)
    fun batch(data: JSONObject): HomeFeedBatch {
        val items = data.getJSONArray("items")
        val batchId = data.getString("batch_id").also { require(it.isNotBlank()) }
        val offset = data.nonNegativeInt("offset")
        return HomeFeedBatch(batchId, offset, List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val details = item.optJSONObject("details") ?: JSONObject()
            val prices = item.optJSONObject("prices")
            val preview = details.optJSONObject("preview")
            val context = if (item.has("catalog_context") && !item.isNull("catalog_context")) {
                val c = item.getJSONObject("catalog_context")
                val revision = c.get("catalog_binding_revision")
                require(revision is Number && revision.toDouble() == revision.toLong().toDouble())
                HomeCatalogContext(c.getString("catalog_id"), c.getString("catalog_item_id"), revision.toLong())
            } else null
            HomeFeedItem(
                id = item.getString("id"), kind = item.getString("kind"), resourceId = item.getString("resource_id"),
                title = item.optString("title"), coverUrl = item.optString("cover_url"), vip = item.optBoolean("vip"),
                fileUrl = details.optString("file_url").ifBlank { details.optString("preview_video_url") },
                previewUrl = details.optString("preview_url"), durationSeconds = details.optDouble("duration", 0.0).toInt(),
                fastPrice = prices?.nullableInt("fast"), qualityPrice = prices?.nullableInt("quality"),
                preview = preview?.let { HomeFeedPreview(it.getString("resolver_url"), it.getString("expires_at")) },
                catalogContext = context, resourceRevision = item.optString("resource_revision"), catalogItemRevision = item.optLong("catalog_item_revision"), description = item.optString("description"),
            )
        }, data.optInt("first_two_remaining"))
    }
    fun playback(data: JSONObject): DramaPlaybackGrant {
        val subtitles = data.getJSONArray("subtitles")
        return DramaPlaybackGrant(data.getString("authorization_id"), data.getString("grant"), data.getString("media_resolver"), data.getString("expires_at"),
            List(subtitles.length()) { index ->
                val s = subtitles.getJSONObject(index)
                DramaSubtitle(s.getString("track_id"), s.getString("language"), s.getString("display_name"), s.getString("format"), s.getString("resolver_url"))
            })
    }
    fun drama(data: JSONObject): ShortDramaDetail {
        val tags = data.optJSONArray("tags")
        val episodes = data.getJSONArray("episodes")
        return ShortDramaDetail(data.getString("drama_id"), data.getString("title"), data.optString("poster_url"),
            if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) },
            List(episodes.length()) { index ->
                val e = episodes.getJSONObject(index)
                ShortDramaEpisode(e.optString("episode_id").takeIf { it.isNotBlank() } ?: e.getString("id"), e.getInt("episode_number"), e.optString("title"), e.nullableInt("amount"), e.optBoolean("vip"), e.optBoolean("can_play"), e.optString("access_reason"))
            }, data.optInt("episode_count", episodes.length()), data.optString("description"))
    }
}
