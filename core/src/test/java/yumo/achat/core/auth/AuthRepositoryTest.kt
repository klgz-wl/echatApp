package yumo.achat.core.auth

import yumo.achat.core.config.*
import yumo.achat.core.network.*
import yumo.achat.core.attribution.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Call

class AuthRepositoryTest {
    private val config = AppConfiguration(AccountRules(3, 50, 6, 32), "", "", "", "")
    private val attribution = LoginAttributionProvider {
        attributionPayload(Json.parseToJsonElement("""{"af_status":"Non-organic","campaign":"test-campaign","is_first_launch":true}""").jsonObject,
            ClientIdentity("test.package", "1", 30), "gaid-test", "af-test")
    }
    private class Api : PublicAuthApi, AccountApi {
        var loginRequest: LoginRequest? = null
        var anonymousRequest: AnonymousRequest? = null
        var googleRequest: GoogleRequest? = null
        var fail = false
        override suspend fun login(request: LoginRequest): ApiResponse<AuthResponse> { loginRequest = request; return response() }
        override suspend fun anonymous(request: AnonymousRequest): ApiResponse<AuthResponse> { anonymousRequest = request; return response() }
        override suspend fun google(request: GoogleRequest): ApiResponse<AuthResponse> { googleRequest = request; return response() }
        override fun refresh(request: RefreshRequest): Call<ApiResponse<AuthResponse>> = error("当前测试不使用同步刷新")
        override suspend fun logout(session: Session) = ApiResponse<Unit>(if (fail) 1 else 0)
        override suspend fun deleteAccount(session: Session) = logout(session)
        private fun response() = ApiResponse(if (fail) 1 else 0, data = if (fail) null else AuthResponse("token", "refresh", "user"))
    }
    @Test fun `三种登录均携同契约归因对象且不修改登录设备身份`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val repo = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution, UserDataCleaner { })
        repo.login("alice", "secret"); repo.anonymous(); repo.google("google-token")
        val expected = attribution.forLogin()
        assertEquals(expected, api.loginRequest!!.attribution)
        assertEquals(expected, api.anonymousRequest!!.attribution)
        assertEquals(expected, api.googleRequest!!.attribution)
        assertEquals("test-device", api.anonymousRequest!!.deviceId)
        val requests = listOf(Json.encodeToString(LoginRequest.serializer(), api.loginRequest!!),
            Json.encodeToString(AnonymousRequest.serializer(), api.anonymousRequest!!), Json.encodeToString(GoogleRequest.serializer(), api.googleRequest!!))
        requests.forEach {
            val data = Json.parseToJsonElement(it).jsonObject["attribution"]!!.jsonObject
            assertEquals("appsflyer", data["attribution_source"]!!.jsonPrimitive.content)
            assertEquals("android", data["platform"]!!.jsonPrimitive.content)
            assertEquals("test.package", data["package_name"]!!.jsonPrimitive.content)
            assertEquals("af-test", data["af_uid"]!!.jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), data["extra_data"]!!.jsonObject["is_first_launch"])
        }
    }
    @Test fun `账号去首尾空格而密码原样传输`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val repository = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution, UserDataCleaner { })
        repository.login("  alice  ", " secret ")
        assertEquals("alice", api.loginRequest!!.username)
        assertEquals(" secret ", api.loginRequest!!.password)
        assertEquals("test-device", api.loginRequest!!.deviceId)
        assertEquals("android", api.loginRequest!!.platform)
    }
    @Test fun `长度不合法不调用真实接口`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val repository = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution, UserDataCleaner { })
        for ((account, password) in listOf("ab" to "123456", "a".repeat(51) to "123456", "abc" to "12345", "abc" to "a".repeat(33))) {
            try { repository.login(account, password); fail() } catch (_: ServiceFailure.InvalidAccount) { }
            assertNull(api.loginRequest)
        }
        assertTrue(config.accountRules.accepts("abc", "123456"))
        assertTrue(config.accountRules.accepts("a".repeat(50), "p".repeat(32)))
    }
    @Test fun `匿名接口失败不创建本地登录成功`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api().apply { fail = true }
        val repository = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution, UserDataCleaner { })
        try { repository.anonymous(); fail() } catch (_: ServiceFailure.Business) { }
        assertEquals("test-device", api.anonymousRequest!!.deviceId)
        assertNull(sessions.current)
        api.fail = false
        repository.anonymous()
        assertEquals("user", sessions.current!!.userId)
    }
    @Test fun `退出或注销失败保留当前会话`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val repository = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution, UserDataCleaner { })
        repository.anonymous()
        api.fail = true
        for (delete in listOf(false, true)) {
            try { repository.logout(delete); fail() } catch (_: ServiceFailure.Business) { }
            assertEquals("user", sessions.current!!.userId)
        }
        api.fail = false
        repository.logout()
        assertNull(sessions.current)
    }
    @Test fun `退出成功才清理指定用户且清理完成后清会话`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val cleaned = mutableListOf<String>()
        val repo = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution,
            UserDataCleaner { owner -> assertEquals(owner, sessions.current!!.userId); cleaned += owner })
        repo.anonymous(); api.fail = true
        runCatching { repo.logout() }; assertTrue(cleaned.isEmpty()); assertNotNull(sessions.current)
        api.fail = false; repo.logout(delete = true)
        assertEquals(listOf("user"), cleaned); assertNull(sessions.current)
    }
    @Test fun `服务端退出成功后清理异常也不保留已失效凭据`() = runBlocking {
        val storage = MemoryStorage(); val sessions = SessionCoordinator(storage); val api = Api()
        val repo = ApiAuthRepository(api, api, sessions, storage, ClientIdentity("test.package", "1", 30), config, attribution,
            UserDataCleaner { throw java.io.IOException() })
        repo.anonymous(); assertTrue(runCatching { repo.logout() }.isFailure); assertNull(sessions.current)
    }
}
