package yumo.achat.core.catalog

import yumo.achat.core.auth.MemoryStorage
import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.network.AuthResponse
import yumo.achat.core.visual.VisualGenerationApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class TemplateRequestTest {
    @Test fun `真实Retrofit请求显式发送true和false并保留分类分页参数`() = runBlocking {
        val requests = mutableListOf<HttpUrl>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requests += url
            val data = if (url.encodedPath.endsWith("categories")) """[{"id":"category","name":"分类"}]"""
                else """{"items":[],"page":${url.queryParameter("page")},"page_size":20,"total":0}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":$data}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.test/api/v1/").client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
            .create(VisualGenerationApi::class.java)
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("test-token", "test-refresh", "user"))
        val repository = ApiTemplateRepository(api, sessions, CatalogConfiguration(MediaKind.VIDEO, 20))
        repository.load(CatalogChannel.HOME)
        repository.load(CatalogChannel.HOME, page = 2)
        repository.load(CatalogChannel.VIDEO)
        repository.load(CatalogChannel.VIDEO, "category")
        repository.load(CatalogChannel.VIDEO, "category", page = 2)
        repository.load(CatalogChannel.IMAGE)
        val templates = requests.filter { it.encodedPath.endsWith("templates") }
        assertEquals(listOf("true", "true", "false", "false", "false", null), templates.map { it.queryParameter("home_featured") })
        assertEquals(listOf("1", "2", "1", "1", "2", "1"), templates.map { it.queryParameter("page") })
        assertEquals(listOf(null, null, null, "category", "category", null), templates.map { it.queryParameter("category_id") })
        assertTrue(templates.take(5).all { it.encodedPath == "/api/v1/visual-generation/video/templates" })
        assertEquals("/api/v1/visual-generation/image/templates", templates.last().encodedPath)
        assertTrue(templates.all { it.queryParameter("page_size") == "20" })
        assertTrue(requests.filter { it.encodedPath.endsWith("categories") }.all { it.queryParameter("home_featured") == null })
    }
}
