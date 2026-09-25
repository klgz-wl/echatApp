package yumo.achat.core.catalog

import yumo.achat.core.auth.*
import yumo.achat.core.network.*
import yumo.achat.core.visual.*
import kotlinx.coroutines.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.*
import org.junit.Test

class ApiTemplateRepositoryTest {
    private class Api : VisualGenerationApi {
        val sortQueries = mutableListOf<String>()
        val queries = mutableListOf<Triple<String, Int, String?>>()
        var response: suspend (String, Int) -> VisualPage<VisualTemplate> = { kind, page -> VisualPage(listOf(
            VisualTemplate("$kind-$page", "Backend title", "category", fileUrl = "https://example.test/media", mimeType = "$kind/test", width = 400, height = 600)), page, 2, 3) }
        override suspend fun categories(modality: String, session: Session) = ApiResponse(0, data = listOf(VisualCategory("category", "Backend category")))
        override suspend fun templates(modality: String, page: Int, pageSize: Int, categoryId: String?, sortBy: String, session: Session): ApiResponse<VisualPage<VisualTemplate>> {
            queries += Triple(modality, page, categoryId)
            sortQueries += sortBy
            return ApiResponse(0, data = response(modality, page))
        }
        override suspend fun createTask(modality: String, idempotencyKey: String, templateId: RequestBody, quality: RequestBody, image: MultipartBody.Part?, session: Session): ApiResponse<VisualTask> = error("浏览不能创建任务")
        override suspend fun task(id: String, session: Session): ApiResponse<VisualTask> = error("本测试不查询任务")
        override suspend fun resources(page: Int, pageSize: Int, modality: String?, session: Session): ApiResponse<VisualPage<VisualResource>> = error("本测试不查询作品")
    }
    private suspend fun fixture(): Triple<ApiTemplateRepository, Api, SessionCoordinator> {
        val api = Api(); val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        return Triple(ApiTemplateRepository(api, sessions, CatalogConfiguration(MediaKind.VIDEO, 2)), api, sessions)
    }
    @Test fun `首页与视频分类分页刷新重试均保持热门排序`() = runBlocking {
        val (repository, api) = fixture()
        for (channel in listOf(CatalogChannel.HOME, CatalogChannel.VIDEO)) {
            val start = api.sortQueries.size
            repository.load(channel)
            repository.load(channel, "category")
            repository.load(channel, "category", 2)
            repository.load(channel, "category", 1)
            val normal = api.response
            api.response = { _, _ -> throw java.io.IOException() }
            try { repository.load(channel, "category", 2); fail() } catch (_: java.io.IOException) { }
            api.response = normal
            repository.load(channel, "category", 2)
            assertEquals(List(6) { "hot" }, api.sortQueries.drop(start))
            assertEquals(listOf(null, "category", "category", "category", "category", "category"), api.queries.drop(start).map { it.third })
        }
    }
    @Test fun `图片接口使用最新排序`() = runBlocking {
        val (repository, api, sessions) = fixture()
        repository.load(CatalogChannel.IMAGE)
        repository.load(CatalogChannel.IMAGE, "category", 2)
        ApiTemplateRepository(api, sessions, CatalogConfiguration(MediaKind.IMAGE, 2)).load(CatalogChannel.HOME)
        assertEquals(listOf("latest", "latest", "latest"), api.sortQueries)
        assertTrue(api.queries.all { it.first == "image" })
    }
    @Test fun `动态图封面与实际视频地址分离并兼容旧草稿`() = runBlocking {
        val (repository, api) = fixture()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        api.response = { _, page -> VisualPage(listOf(json.decodeFromString<VisualTemplate>("""{"id":"template","name":"sample","file_url":"https://example.test/video.mp4","preview_url":"https://example.test/cover.webp","mime_type":"video/mp4","width":720,"height":1280,"duration":10.379,"tags":["dance","hot"],"is_home_featured":true,"prices":{"fast":10,"quality":21}}""")), page, 2, 1) }
        val template = repository.load(CatalogChannel.HOME).templates.single()
        assertEquals("https://example.test/video.mp4", template.preview.asset)
        assertEquals("video/mp4", template.preview.mimeType)
        assertEquals("https://example.test/cover.webp", template.cover?.asset)
        assertEquals("image/webp", template.cover?.mimeType)
        assertEquals(listOf("dance", "hot"), template.tags); assertTrue(template.isHomeFeatured)
        assertEquals(template, json.decodeFromString<Template>(json.encodeToString(Template.serializer(), template)))
        val old = json.encodeToString(Template.serializer(), template.copy(cover = null, tags = emptyList(), isHomeFeatured = false))
        assertNull(json.decodeFromString<Template>(old).cover)
    }
    @Test fun `首页和视频列表支持视频预览且详情仍用原视频`() = runBlocking {
        val (repository, api) = fixture(); val normal = api.response
        api.response = { kind, page -> normal(kind, page).let { data -> data.copy(items = data.items.map {
            it.copy(previewUrl = "https://example.test/preview.MP4?signature=webp", fileUrl = "https://example.test/original.mp4", mimeType = "video/mp4")
        }) } }
        for (channel in listOf(CatalogChannel.HOME, CatalogChannel.VIDEO)) {
            val template = repository.load(channel).templates.single()
            assertEquals("video/mp4", template.cover?.mimeType)
            assertEquals("https://example.test/preview.MP4?signature=webp", template.cover?.asset)
            assertEquals("https://example.test/original.mp4", template.preview.asset)
        }
    }
    @Test fun `个别模板尺寸缺失不阻断其他模板报价且分页仍按原页计算`() = runBlocking {
        val (repository, api) = fixture(); val normal = api.response
        api.response = { kind, page ->
            val data = normal(kind, page)
            data.copy(items = data.items.map { it.copy(prices = TemplatePrices(10, 20)) } + data.items.map { it.copy(id = "bad", width = 0, height = 0) })
        }
        val result = repository.load(CatalogChannel.VIDEO)
        assertEquals(1, result.templates.size); assertEquals(10L, result.templates.single().prices?.fast)
        assertTrue(result.hasMore)
    }
    @Test fun `图片视频模板JSON价格映射并保留缺失零价和大额`() = runBlocking {
        val (repository, api) = fixture()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (prices in listOf("{\"fast\":7,\"quality\":12}", "{\"fast\":0,\"quality\":3000000000}", "{\"fast\":-1}", "null")) {
            api.response = { kind, page ->
                val value = json.decodeFromString<VisualTemplate>("""{"id":"id","name":"name","file_url":"https://example.test/a","mime_type":"$kind/test","width":10,"height":10,"prices":$prices}""")
                VisualPage(listOf(value), page, 2, 1)
            }
            for (channel in listOf(CatalogChannel.IMAGE, CatalogChannel.VIDEO)) {
                val template = repository.load(channel).templates.single()
                val restored = json.decodeFromString<Template>(json.encodeToString(Template.serializer(), template))
                assertEquals(template.prices, restored.prices)
                when (prices) {
                    "{\"fast\":7,\"quality\":12}" -> { assertEquals(7L, restored.prices?.forQuality("fast")); assertEquals(12L, restored.prices?.forQuality("quality")) }
                    "{\"fast\":0,\"quality\":3000000000}" -> { assertEquals(0L, restored.prices?.forQuality("fast")); assertEquals(3000000000L, restored.prices?.forQuality("quality")) }
                    else -> { assertNull(restored.prices?.forQuality("fast")); assertNull(restored.prices?.forQuality("quality")) }
                }
                assertNull(restored.prices?.forQuality("unknown"))
            }
        }
        assertNull(repository.load(CatalogChannel.IMAGE).templates.single().prices)
    }
    @Test fun `服务端回环地址不作为设备媒体请求`() {
        assertFalse(TemplateMedia("http://localhost:8081/a.mp4", 10, 10, remote = true).canLoad)
        assertFalse(TemplateMedia("http://127.0.0.1/a", 10, 10, remote = true).canLoad)
        assertFalse(TemplateMedia("http://[::1]/a", 10, 10, remote = true).canLoad)
        assertTrue(TemplateMedia("https://cdn.example.test/a.webp", 10, 10, remote = true).canLoad)
    }
    @Test fun `频道路径与后台名称保持对应且缺失标记不编造`() = runBlocking {
        val (repository, api) = fixture()
        val home = repository.load(CatalogChannel.HOME)
        repository.load(CatalogChannel.VIDEO, "category")
        val image = repository.load(CatalogChannel.IMAGE)
        assertEquals(listOf("video", "video", "image"), api.queries.map { it.first })
        assertEquals("category", api.queries[1].third)
        assertEquals("Backend title", home.templates.single().title)
        assertFalse(home.templates.single().textIsResource)
        assertFalse(home.templates.single().hasVoice)
        assertFalse(home.templates.single().isNew)
        assertNull(image.templates.single().durationSeconds)
    }
    @Test fun `分页采用服务端总数与页码不虚构更多`() = runBlocking {
        val (repository, api) = fixture()
        assertTrue(repository.load(CatalogChannel.VIDEO).hasMore)
        assertFalse(repository.load(CatalogChannel.VIDEO, page = 2).hasMore)
        api.response = { _, page -> VisualPage(emptyList(), page, 2, 0) }
        val empty = repository.load(CatalogChannel.VIDEO)
        assertTrue(empty.templates.isEmpty()); assertFalse(empty.hasMore)
    }
    @Test fun `请求失败后重试同页不回退mock`() = runBlocking {
        val (repository, api) = fixture(); val normal = api.response
        api.response = { _, _ -> throw java.io.IOException() }
        try { repository.load(CatalogChannel.VIDEO, page = 2); fail() } catch (_: java.io.IOException) { }
        api.response = normal
        assertEquals(2, repository.load(CatalogChannel.VIDEO, page = 2).page)
        assertEquals(listOf(2, 2), api.queries.map { it.second })
    }
    @Test fun `旧账号延迟响应不能返回给新账号`() = runBlocking {
        val (repository, api, sessions) = fixture(); val normal = api.response
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.response = { kind, page -> started.complete(Unit); release.await(); normal(kind, page) }
        val result = async { runCatching { repository.load(CatalogChannel.VIDEO) } }
        started.await(); sessions.saveLogin(AuthResponse("new", "refresh", "other")); release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is ServiceFailure.Superseded)
    }
    @Test fun `不存在的分类回到全部并拒绝错误页码`() = runBlocking {
        val (repository, api) = fixture(); val normal = api.response
        repository.load(CatalogChannel.IMAGE, "removed"); assertNull(api.queries.single().third)
        api.response = { kind, page -> normal(kind, page).copy(page = page + 1) }
        try { repository.load(CatalogChannel.IMAGE); fail() } catch (_: ServiceFailure.InvalidResponse) { }
    }
}
