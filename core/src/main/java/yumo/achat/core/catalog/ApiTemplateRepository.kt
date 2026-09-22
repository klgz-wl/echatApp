package yumo.achat.core.catalog

import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ServiceFailure
import yumo.achat.core.visual.VisualGenerationApi
import javax.inject.Inject
import javax.inject.Singleton

class CatalogConfiguration(val homeModality: MediaKind, val pageSize: Int) {
    init { require(pageSize in 1..100) }
    fun modality(channel: CatalogChannel) = when (channel) { CatalogChannel.HOME -> homeModality; CatalogChannel.VIDEO -> MediaKind.VIDEO; CatalogChannel.IMAGE -> MediaKind.IMAGE }
}
@Singleton
class ApiTemplateRepository @Inject constructor(private val api: VisualGenerationApi, private val sessions: SessionCoordinator,
    private val config: CatalogConfiguration) : TemplateRepository {
    override suspend fun load(channel: CatalogChannel, categoryId: String?, page: Int): TemplateCatalog {
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        val kind = config.modality(channel)
        val modality = kind.name.lowercase(java.util.Locale.ROOT)
        val categories = if (page == 1) api.categories(modality, session).requireData() else emptyList()
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        val selected = categoryId?.takeIf { it.isNotBlank() && (page != 1 || categories.any { category -> category.id == it }) }
        // 首页精选与 Video 列表在服务端分开筛选，分页和重试沿用同一频道条件。
        val homeFeatured = if (kind == MediaKind.VIDEO) channel == CatalogChannel.HOME else null
        val result = api.templates(modality, page, config.pageSize, selected, homeFeatured, session).requireData()
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        if (result.page != page || result.pageSize <= 0 || result.total < 0 || categories.any { it.id.isBlank() }) throw ServiceFailure.InvalidResponse
        // 后台个别素材元数据不完整时跳过该项，不能让整页有效模板及报价一起消失。
        val usable = result.items.filter { it.id.isNotBlank() && it.width > 0 && it.height > 0 && it.duration.isFinite() && it.duration >= 0 &&
            (it.fileUrl.startsWith("https://") || it.fileUrl.startsWith("http://")) &&
            (it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")) }
        if (usable.size != result.items.size) timber.log.Timber.w("跳过 %d 个媒体元数据无效的模板", result.items.size - usable.size)
        return TemplateCatalog(
            categories = listOf(TemplateCategory("", "category_all")) + categories.sortedBy { it.sortOrder }.distinctBy { it.id }
                .map { TemplateCategory(it.id, it.name, labelIsResource = false) },
            templates = usable.distinctBy { it.id }.map { value ->
                Template(value.id, value.name, "", listOfNotNull(value.categoryId), kind,
                    TemplateMedia(value.fileUrl, value.width, value.height, remote = true, mimeType = value.mimeType),
                    durationSeconds = value.duration.takeIf { kind == MediaKind.VIDEO && it > 0 }?.toInt(), textIsResource = false, prices = value.prices,
                    cover = value.previewUrl?.takeIf { it.isNotBlank() }?.let { TemplateMedia(it, value.width, value.height, remote = true,
                        mimeType = previewMediaType(it, value.fileUrl, value.mimeType)) },
                    tags = value.tags, isHomeFeatured = value.isHomeFeatured)
            }, remote = true, page = page, hasMore = result.items.isNotEmpty() && page.toLong() * result.pageSize < result.total)
    }
}
