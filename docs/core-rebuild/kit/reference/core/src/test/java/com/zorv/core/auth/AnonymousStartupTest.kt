package com.zorv.core.auth

import com.zorv.core.attribution.*
import com.zorv.core.config.*
import com.zorv.core.network.AuthResponse
import com.zorv.core.network.ApiResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AnonymousStartupTest {
    private class Fixture(result: ConversionResult = ConversionResult.Failed, timeout: Long = 1000) {
        val storage = MemoryStorage()
        val sessions = SessionCoordinator(storage)
        var online = true
        var calls = 0
        var profileCalls = 0
        var isNew = false
        var fetchProfile: suspend () -> UserProfile = { UserProfile(isNew) }
        val profiles = UserProfileRepository(object : UserProfileApi {
            override suspend fun profile(session: Session): ApiResponse<UserProfile> {
                assertNotNull(sessions.current)
                profileCalls++
                return ApiResponse(0, data = fetchProfile())
            }
        }, sessions)
        var authenticate: suspend () -> Unit = { sessions.saveLogin(AuthResponse("new", "refresh", "anonymous")) }
        private val source = object : ConversionSource {
            override val conversion = MutableStateFlow(result)
            override fun startAttribution() = Unit
            override fun attributionUid() = "af"
            override fun advertisingId(): String? = null
        }
        val coordinator = AttributionCoordinator(source, object : AttributionStorage {
            override suspend fun readAttribution(): JsonObject? = null
            override suspend fun writeAttribution(data: JsonObject) = Unit
        }, storage, ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(20, 10, 5000))
        val startup = AnonymousStartup(object : AuthRepository {
            override suspend fun anonymous() { calls++; authenticate() }
            override suspend fun google(idToken: String) = error("启动不能使用 Google 登录")
            override suspend fun login(account: String, password: String) = error("启动不能使用账号登录")
            override suspend fun logout(delete: Boolean) = error("启动不能退出账号")
        }, sessions, coordinator, profiles, NetworkAvailability { online }, StartupConfiguration(timeout))
    }
    @Test fun `任何归因结果均由登录后的is_new决定AB`() = runBlocking {
        for (result in listOf(
            ConversionResult.Success(buildJsonObject { put("af_status", "Organic") }),
            ConversionResult.Success(buildJsonObject { put("af_status", "Non-organic") }),
            ConversionResult.Failed, ConversionResult.Pending)) {
            for (isNew in listOf(true, false)) {
                val fixture = Fixture(result).apply { this.isNew = isNew }
                assertEquals(if (isNew) AppMode.B else AppMode.A, fixture.startup.start())
                assertEquals(1, fixture.calls)
                assertEquals(1, fixture.profileCalls)
                assertEquals("anonymous", fixture.sessions.current?.userId)
            }
        }
    }
    @Test fun `资料失败不能猜测模式且重试后使用真实资料`() = runBlocking {
        val fixture = Fixture()
        fixture.fetchProfile = { throw IOException() }
        assertTrue(runCatching { fixture.startup.start() }.exceptionOrNull() is IOException)
        assertNull(fixture.profiles.state.value)
        fixture.fetchProfile = { UserProfile(true) }
        assertEquals(AppMode.B, fixture.startup.start())
    }
    @Test fun `离线不跳首页恢复网络后可重试匿名登录`() = runBlocking {
        val fixture = Fixture(); fixture.online = false
        assertTrue(runCatching { fixture.startup.start() }.exceptionOrNull() is StartupOfflineException)
        assertEquals(0, fixture.calls); assertNull(fixture.sessions.current)
        fixture.online = true
        assertEquals(AppMode.A, fixture.startup.start()); assertEquals(1, fixture.calls)
    }
    @Test fun `旧账号会话不能绕过匿名认证失败`() = runBlocking {
        val fixture = Fixture()
        fixture.sessions.saveLogin(AuthResponse("old", "refresh", "old-user"))
        fixture.authenticate = { throw IOException() }
        assertTrue(runCatching { fixture.startup.start() }.exceptionOrNull() is IOException)
        assertEquals(1, fixture.calls)
        fixture.authenticate = { fixture.sessions.saveLogin(AuthResponse("new", "refresh", "anonymous")) }
        assertEquals(AppMode.A, fixture.startup.start()); assertEquals("anonymous", fixture.sessions.current?.userId)
    }
    @Test fun `认证超时和没有实际会话均不能返回成功`() = runBlocking {
        val slow = Fixture(timeout = 40); slow.authenticate = { awaitCancellation() }
        assertTrue(runCatching { slow.startup.start() }.exceptionOrNull() is StartupUnavailableException)
        val empty = Fixture(); empty.authenticate = { }
        assertTrue(runCatching { empty.startup.start() }.exceptionOrNull() is StartupUnavailableException)
    }
}
