package com.zorv.core.visual

import com.zorv.core.auth.*
import com.zorv.core.catalog.*
import com.zorv.core.network.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class GenerationRequestRepositoryTest {
    private class Storage : GenerationStorage {
        val file = File.createTempFile("vexora-test-", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val records = mutableMapOf<String, List<GenerationRequest>>()
        var failWrite = false
        override suspend fun read(userId: String) = records[userId].orEmpty()
        override suspend fun write(userId: String, requests: List<GenerationRequest>) { if (failWrite) throw IOException(); records[userId] = requests }
        override fun photoFile(photo: SourcePhoto) = file
    }
    private class Api : VisualGenerationApi {
        val keys = mutableListOf<String>()
        var response: suspend (String) -> ApiResponse<VisualTask> = { kind -> ApiResponse(0, data = taskValue(kind)) }
        var inspect: (MultipartBody.Part?) -> Unit = {}
        override suspend fun createTask(modality: String, idempotencyKey: String, templateId: RequestBody, quality: RequestBody, image: MultipartBody.Part?, session: Session): ApiResponse<VisualTask> {
            keys += idempotencyKey
            assertEquals("template", Buffer().also { templateId.writeTo(it) }.readUtf8())
            assertEquals("fast", Buffer().also { quality.writeTo(it) }.readUtf8())
            inspect(image)
            return response(modality)
        }
        override suspend fun categories(modality: String, session: Session): ApiResponse<List<VisualCategory>> = error("非本测试范围")
        override suspend fun templates(modality: String, page: Int, pageSize: Int, categoryId: String?, homeFeatured: Boolean?, session: Session): ApiResponse<VisualPage<VisualTemplate>> = error("非本测试范围")
        override suspend fun task(id: String, session: Session): ApiResponse<VisualTask> = ApiResponse(0, data = taskValue("image").copy(status = "failed", refunded = true, refundAmount = 7))
        override suspend fun resources(page: Int, pageSize: Int, modality: String?, session: Session): ApiResponse<VisualPage<VisualResource>> = error("非本测试范围")
    }
    private data class Fixture(val repo: GenerationRequestRepository, val api: Api, val storage: Storage, val sessions: SessionCoordinator, val request: GenerationRequest)
    private suspend fun fixture(events: com.zorv.core.analytics.EventTracker = com.zorv.core.analytics.NoOpEventTracker): Fixture {
        val api = Api(); val storage = Storage(); val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val request = GenerationRequest(userId = "user", template = Template("template", "title", "", emptyList(), MediaKind.IMAGE,
            TemplateMedia("https://example.test/a.png", 10, 10, true), prices = TemplatePrices(7, 12)), quality = "fast", photo = SourcePhoto(storage.file.name, "image/png", 10, 10, 3))
        return Fixture(GenerationRequestRepository(api, sessions, storage, Json, events), api, storage, sessions, request)
    }
    @Test fun `上传受理与生成终态分开记录且恢复轮询不重复终态`() = runBlocking {
        val events = com.zorv.core.analytics.RecordingTracker(); val f = fixture(events)
        val task = f.repo.submit(f.request)
        assertEquals("upload_img_result", events.events.single().name)
        assertEquals("task_submit", events.events.single().values["phase"])
        val request = f.request.copy(task = task)
        f.repo.refresh(request); f.repo.pending("user"); f.repo.refresh(request)
        val result = events.events.filter { it.name == "generate_result" }.single()
        assertEquals(false, result.values["is_success"]); assertEquals(7L, result.values["diamond_cost"])
        assertEquals("user", result.user); assertNotNull(result.once)
    }
    @Test fun `无报价和负报价不上传零价允许且实际扣费遵从响应`() = runBlocking {
        for (prices in listOf(null, TemplatePrices(-1, 12), TemplatePrices(quality = 12))) {
            val f = fixture()
            val result = runCatching { f.repo.submit(f.request.copy(template = f.request.template.copy(prices = prices))) }
            assertEquals(GenerationFailure.UNAVAILABLE, (result.exceptionOrNull() as GenerationException).reason)
            assertTrue(f.api.keys.isEmpty()); assertTrue(f.storage.read("user").isEmpty())
        }
        val free = fixture()
        val task = free.repo.submit(free.request.copy(template = free.request.template.copy(prices = TemplatePrices(0, 12))))
        assertEquals(7L, task.diamondCost)
    }
    @Test fun `余额不足后充值再试沿用幂等键与模板价格`() = runBlocking {
        val f = fixture(); f.api.response = { ApiResponse(400101) }
        assertTrue(runCatching { f.repo.submit(f.request) }.isFailure)
        val restored = Json.decodeFromString<GenerationRequest>(Json.encodeToString(GenerationRequest.serializer(), f.storage.read("user").single()))
        assertEquals(7L, restored.template.prices?.forQuality(restored.quality))
        f.api.response = { ApiResponse(0, data = taskValue(it)) }
        f.repo.submit(restored)
        assertEquals(listOf(f.request.key, f.request.key), f.api.keys)
    }
    @Test fun `发送前持久化并正确携带源图和模式且并发不重复创建`() = runBlocking {
        val f = fixture()
        f.api.inspect = { image ->
            assertTrue(f.storage.records["user"]!!.single().submitted)
            assertTrue(image!!.headers!!["Content-Disposition"]!!.contains("name=\"image\""))
            assertArrayEquals(byteArrayOf(1, 2, 3), Buffer().also { image.body.writeTo(it) }.readByteArray())
        }
        val results = (1..5).map { async { f.repo.submit(f.request) } }.awaitAll()
        assertEquals(1, f.api.keys.size); assertEquals(1, results.map { it.taskId }.distinct().size)
        assertEquals(7L, f.storage.records["user"]!!.single().task!!.diamondCost)
    }
    @Test fun `超时与进程恢复重放同一幂等键`() = runBlocking {
        val f = fixture(); f.api.response = { throw IOException() }
        assertEquals(GenerationFailure.UNKNOWN, (runCatching { f.repo.submit(f.request) }.exceptionOrNull() as GenerationException).reason)
        val restored = f.storage.read("user").single()
        f.api.response = { ApiResponse(0, data = taskValue(it)) }
        GenerationRequestRepository(f.api, f.sessions, f.storage, Json).submit(restored)
        assertEquals(listOf(f.request.key, f.request.key), f.api.keys)
    }
    @Test fun `取消网络请求后保留已写入请求以便恢复`() = runBlocking {
        val f = fixture(); val entered = CompletableDeferred<Unit>()
        f.api.response = { entered.complete(Unit); awaitCancellation() }
        val job = launch { f.repo.submit(f.request) }; entered.await(); job.cancelAndJoin()
        assertTrue(f.storage.read("user").single().submitted)
        assertNull(f.storage.read("user").single().task)
    }
    @Test fun `同键改模式拒绝且存储失败不发网络请求`() = runBlocking {
        val f = fixture(); f.repo.submit(f.request)
        assertEquals(GenerationFailure.CONFLICT, (runCatching { f.repo.submit(f.request.copy(quality = "quality")) }.exceptionOrNull() as GenerationException).reason)
        val other = fixture(); other.storage.failWrite = true
        assertTrue(runCatching { other.repo.submit(other.request) }.isFailure); assertTrue(other.api.keys.isEmpty())
    }
    @Test fun `HTTP业务错误准确归类并保持原请求`() = runBlocking {
        for ((status, body, expected) in listOf(Triple(402, "400101", GenerationFailure.INSUFFICIENT),
            Triple(409, "400409", GenerationFailure.CONFLICT), Triple(503, "500001", GenerationFailure.UNAVAILABLE),
            Triple(400, "400001", GenerationFailure.INVALID_IMAGE))) {
            val f = fixture(); f.api.response = { throw HttpException(retrofit2.Response.error<Any>(status, "{\"code\":$body}".toResponseBody())) }
            assertEquals(expected, (runCatching { f.repo.submit(f.request) }.exceptionOrNull() as GenerationException).reason)
            assertEquals(f.request.key, f.storage.read("user").single().key)
        }
    }
    @Test fun `旧账号回包不发布到新账号且不接纳错误任务`() = runBlocking {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.api.response = { entered.complete(Unit); release.await(); ApiResponse(0, data = taskValue(it)) }
        val result = async { runCatching { f.repo.submit(f.request) } }
        entered.await(); f.sessions.saveLogin(AuthResponse("other", "refresh", "other")); release.complete(Unit)
        assertEquals(GenerationFailure.SIGNED_OUT, (result.await().exceptionOrNull() as GenerationException).reason)
        assertTrue(f.storage.read("other").isEmpty()); assertNull(f.storage.read("user").single().task)
        val bad = fixture(); bad.api.response = { ApiResponse(0, data = taskValue(it).copy(templateId = "other-template")) }
        assertEquals(GenerationFailure.UNKNOWN, (runCatching { bad.repo.submit(bad.request) }.exceptionOrNull() as GenerationException).reason)
    }
    @Test fun `查询服务端终态持久保存退款而非本地修改金币`() = runBlocking {
        val f = fixture(); f.repo.submit(f.request)
        val next = f.repo.refresh(f.storage.read("user").single())
        assertEquals("failed", next.status); assertTrue(next.refunded)
        val restored = GenerationRequestRepository(f.api, f.sessions, f.storage, Json).pending("user").single()
        assertEquals(7L, restored.task!!.refundAmount)
        assertEquals(1, f.api.keys.size)
    }
    @Test fun `终态通知确认后重启保持隐藏且不删除任务和源图`() = runBlocking {
        for (status in listOf("succeeded", "failed")) {
            val f = fixture()
            val request = f.request.copy(task = taskValue("image").copy(status = status))
            val other = request.copy(key = "other")
            f.storage.write("user", listOf(request, other))
            val acknowledged = f.repo.acknowledgeHomeNotice(request, f.sessions.current!!.epoch)!!
            assertTrue(acknowledged.homeNoticeDismissed)
            // 使用实际 JSON 格式模拟落盘与进程重建；兼容未包含新字段的旧记录。
            val oldJson = Json.encodeToString(GenerationRequest.serializer(), request)
            assertFalse(oldJson.contains("homeNoticeDismissed"))
            assertFalse(Json.decodeFromString<GenerationRequest>(oldJson).homeNoticeDismissed)
            val restored = Json.decodeFromString<List<GenerationRequest>>(Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GenerationRequest.serializer()), f.storage.read("user")))
            f.storage.write("user", restored)
            val restarted = GenerationRequestRepository(f.api, f.sessions, f.storage, Json)
            assertEquals(listOf(request.key, other.key), restarted.pending("user").map { it.key })
            assertTrue(restarted.pending("user").first().homeNoticeDismissed)
            assertEquals(request.task, restarted.pending("user").first().task)
            assertNull(restarted.acknowledgeHomeNotice(request, f.sessions.current!!.epoch))
            assertTrue(f.storage.file.isFile)
        }
    }
    @Test fun `处理中可重复查看终态并发点击只确认一次`() = runBlocking {
        val f = fixture(); f.repo.submit(f.request)
        val epoch = f.sessions.current!!.epoch
        repeat(2) { assertFalse(f.repo.acknowledgeHomeNotice(f.request, epoch)!!.homeNoticeDismissed) }
        f.repo.refresh(f.request)
        val results = (1..5).map { async { f.repo.acknowledgeHomeNotice(f.request, epoch) } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertTrue(f.repo.pending("user").single().homeNoticeDismissed)
    }
    @Test fun `已查看失败任务继续接收退款且旧轮询请求不能复活通知`() = runBlocking {
        val f = fixture()
        val stale = f.request.copy(task = taskValue("image").copy(status = "failed"))
        f.storage.write("user", listOf(stale))
        f.repo.acknowledgeHomeNotice(stale, f.sessions.current!!.epoch)
        f.repo.refresh(stale)
        val saved = f.repo.pending("user").single()
        assertTrue(saved.homeNoticeDismissed); assertTrue(saved.task!!.refunded)
        assertEquals(7L, saved.task.refundAmount)
    }
    @Test fun `确认写入失败保持可重试且会话变更不消费旧通知`() = runBlocking {
        val f = fixture()
        val request = f.request.copy(task = taskValue("image").copy(status = "failed"))
        f.storage.write("user", listOf(request))
        val epoch = f.sessions.current!!.epoch
        f.storage.failWrite = true
        assertTrue(runCatching { f.repo.acknowledgeHomeNotice(request, epoch) }.exceptionOrNull() is IOException)
        assertFalse(f.repo.pending("user").single().homeNoticeDismissed)
        f.storage.failWrite = false
        f.sessions.saveLogin(AuthResponse("new", "refresh", "user"))
        assertTrue(runCatching { f.repo.acknowledgeHomeNotice(request, epoch) }.exceptionOrNull() is ServiceFailure.Superseded)
        f.sessions.saveLogin(AuthResponse("other", "refresh", "other"))
        assertTrue(runCatching { f.repo.acknowledgeHomeNotice(request, f.sessions.current!!.epoch) }.exceptionOrNull() is ServiceFailure.Superseded)
        assertFalse(f.repo.pending("user").single().homeNoticeDismissed)
        assertTrue(f.repo.pending("other").isEmpty())
    }
    companion object {
        private fun taskValue(kind: String) = VisualTask("task", "processing", kind, "fast", "template", 7, createdAt = "2026-09-10T00:00:00Z")
    }
}
