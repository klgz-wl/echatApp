package com.zorv.core.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CatalogChannel { HOME, VIDEO, IMAGE }
@Serializable enum class MediaKind { VIDEO, IMAGE }
@Serializable enum class CategoryIcon { HOT, TRENDING, NEW, NONE }
@Serializable data class TemplateCategory(val id: String, val label: String, val icon: CategoryIcon = CategoryIcon.NONE, val labelIsResource: Boolean = true)
@Serializable data class TemplateMedia(val asset: String, val width: Int, val height: Int, val remote: Boolean = false, val mimeType: String = "") {
    init { require(asset.isNotBlank() && width >= 0 && height >= 0 && (remote || hasDimensions)) }
    // 远端作品可缺少尺寸；固定卡片及全屏预览直接由图片解码器／播放器读取真实比例。
    val hasDimensions: Boolean get() = width > 0 && height > 0
    val aspectRatio: Float get() {
        check(hasDimensions) { "未知媒体尺寸不能用于计算布局比例" }
        return width.toFloat() / height
    }
    val canLoad: Boolean get() {
        if (!remote) return true
        val uri = runCatching { java.net.URI(asset) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("[")?.removeSuffix("]") ?: return false
        return uri.scheme in listOf("https", "http") && host != "localhost" && !host.endsWith(".localhost") &&
            !host.startsWith("127.") && host != "::1" && host != "0.0.0.0"
    }
}
/** 生成前金额来自模板；缺失和负值不可提交，零表示服务端明确免费。 */
@Serializable data class TemplatePrices(val fast: Long? = null, val quality: Long? = null) {
    fun forQuality(id: String): Long? = when (id) {
        "fast" -> fast
        "quality" -> quality
        else -> null
    }?.takeIf { it >= 0 }
}
@Serializable data class Template(
    val id: String, val title: String, val description: String, val categories: List<String>,
    val mediaKind: MediaKind, val preview: TemplateMedia, val widePreview: TemplateMedia? = null,
    val alternatePreview: TemplateMedia? = null, val durationSeconds: Int? = null, val hasVoice: Boolean = false, val isNew: Boolean = false, val textIsResource: Boolean = true,
    val prices: TemplatePrices? = null,
    val cover: TemplateMedia? = null, val tags: List<String> = emptyList(), val isHomeFeatured: Boolean = false,
)
@Serializable data class TemplateCatalog(val categories: List<TemplateCategory>, val templates: List<Template>, val remote: Boolean = false, val page: Int = 1, val hasMore: Boolean = false) {
    fun filtered(categoryId: String?): List<Template> = if (remote || categoryId == null) templates else templates.filter { categoryId in it.categories }
    fun validate(): TemplateCatalog = apply {
        require(categories.map { it.id }.distinct().size == categories.size)
        require(templates.map { it.id }.distinct().size == templates.size)
        require(templates.all { template -> template.categories.all { id -> categories.any { it.id == id } } })
    }
}
@Serializable data class CatalogFixture(val delayMs: Long = 0, val failAttempts: Int = 0, val catalog: TemplateCatalog)
fun interface CatalogAssetSource { suspend fun read(path: String): String }
interface TemplateRepository { suspend fun load(channel: CatalogChannel, categoryId: String? = null, page: Int = 1): TemplateCatalog }

/** 各入口独立配置路径；演示异常只影响模板读取，不涉及真实认证、钱包或上传。 */
class MockTemplateRepository(private val source: CatalogAssetSource, private val paths: Map<String, String>, private val json: Json) : TemplateRepository {
    private val attempts = java.util.concurrent.ConcurrentHashMap<CatalogChannel, Int>()
    override suspend fun load(channel: CatalogChannel, categoryId: String?, page: Int): TemplateCatalog = withContext(Dispatchers.IO) {
        val fixture = json.decodeFromString<CatalogFixture>(source.read(requireNotNull(paths[channel.name])))
        require(fixture.delayMs >= 0 && fixture.failAttempts >= 0)
        delay(fixture.delayMs)
        val attempt = attempts.merge(channel, 1, Int::plus) ?: 1
        if (attempt <= fixture.failAttempts) throw java.io.IOException()
        fixture.catalog.validate()
    }
}
