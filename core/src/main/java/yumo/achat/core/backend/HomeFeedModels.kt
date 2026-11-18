package yumo.achat.core.backend

import java.util.UUID

data class HomeCatalogContext(val catalogId: String, val catalogItemId: String, val catalogBindingRevision: Long) : java.io.Serializable {
    init {
        require(listOf(catalogId, catalogItemId).all { value -> runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false) })
        require(catalogBindingRevision > 0)
    }
}
data class HomeFeedPreview(val resolverUrl: String, val expiresAt: String)
data class HomeFeedItem(
    val id: String, val kind: String, val resourceId: String, val title: String, val coverUrl: String, val vip: Boolean,
    val fileUrl: String = "", val previewUrl: String = "", val durationSeconds: Int = 0,
    val fastPrice: Int? = null, val qualityPrice: Int? = null, val preview: HomeFeedPreview? = null,
    val catalogContext: HomeCatalogContext? = null, val resourceRevision: String = "", val catalogItemRevision: Long = 0, val description: String = "",
)
data class HomeFeedBatch(val batchId: String, val offset: Int, val items: List<HomeFeedItem>, val firstTwoRemaining: Int = 0)
data class ResolvedDramaPreview(val url: String, val expiresAt: String)
data class ShortDramaEpisode(val episodeId: String, val episodeNumber: Int, val title: String, val amount: Int?, val vip: Boolean, val canPlay: Boolean, val accessReason: String)
data class ShortDramaDetail(val dramaId: String, val title: String, val posterUrl: String, val tags: List<String>, val episodes: List<ShortDramaEpisode>, val episodeCount: Int, val description: String = "")
class HomeFeedServiceException(val code: String, statusCode: Int = 0) : AchatBackendHttpException(statusCode, code)
interface HomeFeedGateway {
    suspend fun homeFeed(requestId: String): HomeFeedBatch
    suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String)
    suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview
    suspend fun shortDramaDetail(dramaId: String): ShortDramaDetail
}

data class DramaSubtitle(val trackId: String, val language: String, val displayName: String, val format: String, val resolverUrl: String)
data class DramaPlaybackGrant(val authorizationId: String, val grant: String, val mediaResolver: String, val expiresAt: String, val subtitles: List<DramaSubtitle>)
interface ShortDramaPlaybackGateway : HomeFeedGateway {
    suspend fun playDramaEpisode(dramaId: String, episodeId: String): DramaPlaybackGrant
    suspend fun refreshDramaGrant(grant: String): DramaPlaybackGrant
    suspend fun resolveDramaMedia(resolverUrl: String): ResolvedDramaPreview
    suspend fun resolveDramaSubtitle(resolverUrl: String): ResolvedDramaPreview
    suspend fun unlockDramaEpisode(dramaId: String, episodeId: String, confirmedAmount: Int, idempotencyKey: String)
}
