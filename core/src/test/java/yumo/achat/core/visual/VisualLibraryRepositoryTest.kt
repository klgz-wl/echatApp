package yumo.achat.core.visual

import yumo.achat.core.auth.*
import yumo.achat.core.network.*
import kotlinx.coroutines.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test

class VisualLibraryRepositoryTest {
    private class Api : VisualGenerationApi {
        val pages = mutableListOf<Pair<Int, String?>>()
        var response: suspend (Int, String?) -> VisualPage<VisualResource> = { page, kind -> VisualPage(listOf(VisualResource("r$page", "t$page", kind ?: "image",
            "template", "Name", "https://example.test/image.png", mimeType = "image/png", width = 10, height = 20, createdAt = "now")), page, 2, 3) }
        override suspend fun resources(page: Int, pageSize: Int, modality: String?, session: Session): ApiResponse<VisualPage<VisualResource>> {
            pages += page to modality; return ApiResponse(0, data = response(page, modality))
        }
        override suspend fun categories(modality: String, session: Session): ApiResponse<List<VisualCategory>> = error("非本测试范围")
        override suspend fun templates(modality: String, page: Int, pageSize: Int, categoryId: String?, homeFeatured: Boolean?, session: Session): ApiResponse<VisualPage<VisualTemplate>> = error("非本测试范围")
        override suspend fun createTask(modality: String, idempotencyKey: String, templateId: RequestBody, quality: RequestBody, image: MultipartBody.Part?, session: Session): ApiResponse<VisualTask> = error("查询不得创建任务")
        override suspend fun task(id: String, session: Session): ApiResponse<VisualTask> = error("非本测试范围")
    }
    private suspend fun fixture(): Triple<VisualLibraryRepository, Api, SessionCoordinator> {
        val api = Api(); val sessions = SessionCoordinator(MemoryStorage()); sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        return Triple(VisualLibraryRepository(api, sessions, VisualPollingConfiguration(5, 2, 60, 2)), api, sessions)
    }
    @Test fun `作品按页码和独立媒体条件请求且保留真实字段`() = runBlocking {
        val (repo, api) = fixture(); val first = repo.resources(1, null); repo.resources(2, "video"); repo.resources(1, "image")
        assertEquals(listOf(1 to null, 2 to "video", 1 to "image"), api.pages)
        assertEquals("Name", first.items.single().templateName); assertEquals(3L, first.total)
    }
    @Test fun `作品失败不伪造空成功结果且错误页码被拒绝`() = runBlocking {
        val (repo, api) = fixture(); val normal = api.response
        api.response = { _, _ -> throw java.io.IOException() }
        assertTrue(runCatching { repo.resources(2, null) }.exceptionOrNull() is java.io.IOException)
        api.response = { page, kind -> normal(page, kind).copy(page = page + 1) }
        assertTrue(runCatching { repo.resources(2, null) }.exceptionOrNull() is ServiceFailure.InvalidResponse)
    }
    @Test fun `服务端成功作品尺寸为零仍可展示且保留分页与真实元数据`() = runBlocking {
        val (repo, api) = fixture()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // 复现设备响应形状，仅替换标识、名称与 URL，不保存用户作品数据。
        val response = json.decodeFromString<ApiResponse<VisualPage<VisualResource>>>("""
            {"code":0,"message":"success","data":{"items":[{
                "id":"resource","task_id":"task","resource_type":"generated","modality":"image",
                "template_id":"template","template_name":"Name","url":"https://example.test/result.png",
                "thumbnail_url":"https://example.test/result.png","mime_type":"image/png",
                "width":0,"height":0,"duration":0,"created_at":"2026-09-14T00:00:00Z","expires_at":"2026-09-21T00:00:00Z"
            }],"page":1,"page_size":20,"total":1}}
        """.trimIndent()).requireData()
        api.response = { _, _ -> response }
        val result = repo.resources(1, null)
        assertEquals(1L, result.total); assertEquals(0, result.items.single().width); assertEquals(0, result.items.single().height)
        for ((width,height) in listOf(0 to 720, 1280 to 0, 0 to 0)) {
            api.response = { _, _ -> response.copy(items = response.items.map { it.copy(modality="video", mimeType="video/mp4", width=width, height=height) }) }
            assertEquals(1, repo.resources(1, "video").items.size)
        }
    }
    @Test fun `兼容未知尺寸不会放过负尺寸错误来源或非法资源地址`() = runBlocking {
        val (repo, api) = fixture(); val normal = api.response(1, null)
        val item = normal.items.single()
        for (bad in listOf(item.copy(width = -1), item.copy(height = -1), item.copy(taskId = ""),
            item.copy(url = "file:///private/photo.png"), item.copy(modality = "unknown"))) {
            api.response = { _, _ -> normal.copy(items = listOf(bad)) }
            assertTrue(runCatching { repo.resources(1, null) }.exceptionOrNull() is ServiceFailure.InvalidResponse)
        }
    }
    @Test fun `旧账号延迟作品不会发布到新账号`() = runBlocking {
        val (repo, api, sessions) = fixture(); val normal = api.response
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.response = { page, kind -> entered.complete(Unit); release.await(); normal(page, kind) }
        val result = async { runCatching { repo.resources(1, null) } }; entered.await()
        sessions.saveLogin(AuthResponse("new", "refresh", "other")); release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is ServiceFailure.Superseded)
    }
    @Test fun `终态不回退但失败后的真实退款可更新`() {
        val processing = VisualTask("t", "processing", "image", "fast", "template", 7, createdAt = "now")
        val failed = processing.copy(status = "failed")
        val refunded = failed.copy(refunded = true, refundAmount = 7)
        assertEquals(failed, GenerationRequestRepository.reconcile(failed, processing))
        assertEquals(refunded, GenerationRequestRepository.reconcile(failed, refunded))
        assertEquals(refunded, GenerationRequestRepository.reconcile(refunded, failed))
        val done = processing.copy(status = "succeeded")
        assertEquals(done, GenerationRequestRepository.reconcile(done, failed))
    }
    @Test fun `异任务及非法退款不能覆盖已知收费`() {
        val task = VisualTask("t", "processing", "image", "fast", "template", 7, createdAt = "now")
        for (bad in listOf(task.copy(taskId = "other"), task.copy(diamondCost = 8), task.copy(refundAmount = 9), task.copy(status = "unknown")))
            assertTrue(runCatching { GenerationRequestRepository.reconcile(task, bad) }.exceptionOrNull() is ServiceFailure.InvalidResponse)
    }
    @Test fun `轮询采用服务器间隔并限制异常值`() {
        val config = VisualPollingConfiguration(5, 2, 60, 20)
        val task = VisualTask("t", "processing", "image", "fast", "template", 7, createdAt = "now")
        assertEquals(5, config.interval(task)); assertEquals(12, config.interval(task.copy(pollIntervalSeconds = 12)))
        assertEquals(2, config.interval(task.copy(pollIntervalSeconds = -1))); assertEquals(60, config.interval(task.copy(pollIntervalSeconds = Int.MAX_VALUE)))
    }
}
