package yumo.achat.core.visual

import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.ServiceFailure
import javax.inject.Inject
import javax.inject.Singleton

class VisualPollingConfiguration(val defaultSeconds: Int, val minSeconds: Int, val maxSeconds: Int, val pageSize: Int) {
    init { require(minSeconds > 0 && defaultSeconds in minSeconds..maxSeconds && pageSize in 1..100) }
    fun interval(task: VisualTask?) = (task?.pollIntervalSeconds ?: defaultSeconds).coerceIn(minSeconds, maxSeconds)
}
@Singleton
class VisualLibraryRepository @Inject constructor(private val api: VisualGenerationApi,
    private val sessions: SessionCoordinator, private val config: VisualPollingConfiguration) {
    suspend fun resources(page: Int, modality: String?): VisualPage<VisualResource> {
        require(page > 0 && modality in listOf(null, "image", "video"))
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        val result = api.resources(page, config.pageSize, modality, session).requireData()
        if (result.page != page || result.pageSize !in 1..100 || result.total < 0 || result.items.any {
                // 生成资源的尺寸可能尚未提取，0 表示未知，不能因此拒绝整页作品。
                it.id.isBlank() || it.taskId.isBlank() || it.width < 0 || it.height < 0 || !it.duration.isFinite() || it.duration < 0 ||
                    it.modality !in setOf("image", "video") || (modality != null && it.modality != modality) ||
                    !(it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")) ||
                    runCatching { java.net.URI(it.url).scheme !in listOf("http", "https") }.getOrDefault(true)
            }) throw ServiceFailure.InvalidResponse
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        return result
    }
}
