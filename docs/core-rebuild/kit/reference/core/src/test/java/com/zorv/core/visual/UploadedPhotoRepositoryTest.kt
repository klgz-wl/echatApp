package com.zorv.core.visual

import com.zorv.core.auth.*
import com.zorv.core.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.GET

class UploadedPhotoRepositoryTest {
    private val photo = UploadedPhoto("image", "https://cdn.example.test/image.webp", "image/webp")
    private class Api : UploadedPhotoApi {
        var response: suspend (Int) -> VisualPage<UploadedPhoto> = { VisualPage(emptyList(), it, 2, 0) }
        val pages = mutableListOf<Int>()
        override suspend fun photos(page: Int, pageSize: Int, session: Session): ApiResponse<VisualPage<UploadedPhoto>> {
            pages += page; return ApiResponse(0, data = response(page))
        }
    }
    private suspend fun fixture(): Triple<UploadedPhotoRepository, Api, SessionCoordinator> {
        val api = Api(); val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        return Triple(UploadedPhotoRepository(api, sessions, VisualPollingConfiguration(5, 1, 30, 2)), api, sessions)
    }
    @Test fun `已上传图片使用upload分类和image筛选兼容未知尺寸`() = runBlocking {
        val path = UploadedPhotoApi::class.java.methods.single { it.name == "photos" }.getAnnotation(GET::class.java)!!.value
        assertEquals("visual-generation/resources?resource_type=upload&modality=image", path)
        val value = Json.decodeFromString<UploadedPhoto>("""{"id":"image","url":"https://cdn.example.test/image.webp","mime_type":"image/webp","resource_type":"upload"}""")
        assertEquals(photo, value)
        val (repository, api) = fixture()
        api.response = { VisualPage(listOf(value), it, 2, 3) }
        assertEquals(value, repository.photos(1).items.single()); assertEquals(3, repository.photos(2).total)
        assertEquals(listOf(1, 2), api.pages)
    }
    @Test fun `错误类型危险地址错误分页不可进入选图网格`() = runBlocking {
        val (repository, api) = fixture()
        for (invalid in listOf(photo.copy(resourceType = "generated"), photo.copy(mimeType = "video/mp4"), photo.copy(url = "http://localhost/a"), photo.copy(width = -1))) {
            api.response = { VisualPage(listOf(invalid), it, 2, 1) }
            assertTrue(runCatching { repository.photos(1) }.exceptionOrNull() is ServiceFailure.InvalidResponse)
        }
        api.response = { VisualPage(listOf(photo), it + 1, 2, 1) }
        assertTrue(runCatching { repository.photos(1) }.exceptionOrNull() is ServiceFailure.InvalidResponse)
    }
    @Test fun `旧账号的上传资源响应不得进入新账号`() = runBlocking {
        val (repository, api, sessions) = fixture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.response = { entered.complete(Unit); release.await(); VisualPage(listOf(photo), it, 2, 1) }
        val response = async { runCatching { repository.photos(1) } }
        entered.await(); sessions.saveLogin(AuthResponse("new", "refresh", "other")); release.complete(Unit)
        assertTrue(response.await().exceptionOrNull() is ServiceFailure.Superseded)
    }
}
