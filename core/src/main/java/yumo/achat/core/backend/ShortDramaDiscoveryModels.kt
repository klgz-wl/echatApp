package yumo.achat.core.backend

/** API pages preserve server ordering; no client watch-time or progress is invented. */
data class DramaPage<T>(val items: List<T>, val total: Int, val page: Int, val pageSize: Int) {
    val hasMore: Boolean get() = page.toLong() * pageSize < total
}
data class ShortDramaSummary(val dramaId: String, val title: String, val posterUrl: String,
    val firstEpisodeId: String, val tags: List<String>, val episodeCount: Int, val hasVipEpisodes: Boolean, val hotScore: Int)
data class DramaHistoryEntry(val dramaId: String, val title: String, val posterUrl: String,
    val lastEpisodeId: String, val episodeNumber: Int, val episodeTitle: String, val available: Boolean, val unavailableReason: String)
data class ShortDramaAd(val adId: String, val dramaId: String, val title: String, val copy: String,
    val description: String, val buttonText: String, val action: String)
interface ShortDramaDiscoveryGateway {
    suspend fun shortDramas(page: Int = 1, pageSize: Int = 20, sort: String = "hot", tag: String = ""): DramaPage<ShortDramaSummary>
    suspend fun shortDramaHistory(page: Int = 1, pageSize: Int = 20): DramaPage<DramaHistoryEntry>
    suspend fun shortDramaAds(): List<ShortDramaAd>
}
