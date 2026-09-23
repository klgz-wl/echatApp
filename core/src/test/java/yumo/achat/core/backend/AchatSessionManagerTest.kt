package yumo.achat.core.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import yumo.achat.core.attribution.LoginAttribution

class AchatSessionManagerTest {
    @Test
    fun `401 refreshes access token persists session and retries once`() = runBlocking {
        val store = FakeSessionStore(session("old-access", "refresh-1"))
        val api = FakeAuthApi(refreshResult = Result.success("new-access"))
        val manager = AchatSessionManager(store, api)
        val usedTokens = mutableListOf<String>()

        val result = manager.authenticated { token ->
            usedTokens += token
            if (token == "old-access") throw AchatBackendHttpException(401, "expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("old-access", "new-access"), usedTokens)
        assertEquals("new-access", store.session?.token)
        assertEquals("refresh-1", store.session?.refreshToken)
        assertEquals(1, api.refreshCount.get())
    }

    @Test
    fun `concurrent 401 responses share one refresh request`() = runBlocking {
        val store = FakeSessionStore(session("old-access", "refresh-1"))
        val api = FakeAuthApi(refreshResult = Result.success("new-access"))
        val manager = AchatSessionManager(store, api)
        val oldTokenArrivals = CountDownLatch(2)

        val calls = List(2) {
            async(Dispatchers.Default) {
                manager.authenticated { token ->
                    if (token == "old-access") {
                        oldTokenArrivals.countDown()
                        check(oldTokenArrivals.await(5, TimeUnit.SECONDS))
                        throw AchatBackendHttpException(401, "expired")
                    }
                    token
                }
            }
        }

        assertEquals(listOf("new-access", "new-access"), calls.map { it.await() })
        assertEquals(1, api.refreshCount.get())
    }

    @Test
    fun `invalid refresh falls back to same-device anonymous login`() = runBlocking {
        val oldSession = session("old-access", "bad-refresh")
        val replacement = session("replacement-access", "replacement-refresh")
        val store = FakeSessionStore(oldSession)
        val api = FakeAuthApi(
            refreshResult = Result.failure(AchatBackendHttpException(401, "invalid refresh")),
            loginResult = Result.success(replacement),
        )
        val manager = AchatSessionManager(store, api)

        val result = manager.authenticated { token ->
            if (token == "old-access") throw AchatBackendHttpException(401, "expired")
            token
        }

        assertEquals("replacement-access", result)
        assertEquals("stable-device", api.lastLoginDeviceId)
        assertEquals(replacement, store.session)
    }

    @Test
    fun `failed refresh and failed relogin preserve prior session`() = runBlocking {
        val oldSession = session("old-access", "bad-refresh")
        val store = FakeSessionStore(oldSession)
        val api = FakeAuthApi(
            refreshResult = Result.failure(AchatBackendHttpException(401, "invalid refresh")),
            loginResult = Result.failure(IllegalStateException("offline")),
        )
        val manager = AchatSessionManager(store, api)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                manager.authenticated<String> {
                    throw AchatBackendHttpException(401, "expired")
                }
            }
        }

        assertEquals(oldSession, store.session)
    }

    @Test
    fun `concurrent first use performs one anonymous login`() = runBlocking {
        val replacement = session("access", "refresh")
        val store = FakeSessionStore(null)
        val api = FakeAuthApi(loginResult = Result.success(replacement))
        val attribution = FakeAttribution()
        val manager = AchatSessionManager(store, api, attribution = attribution)

        val calls = List(2) {
            async(Dispatchers.Default) { manager.authenticated { token -> token } }
        }

        assertEquals(listOf("access", "access"), calls.map { it.await() })
        assertEquals(1, api.loginCount.get())
        assertEquals(listOf("stable-device"), attribution.loginDeviceIds)
        assertEquals(loginAttribution(), api.lastLoginAttribution)
        assertEquals(listOf(replacement), attribution.reportedSessions)
    }

    @Test
    fun `existing stored session is shared with attribution reporter`() = runBlocking {
        val existing = session("access", "refresh")
        val store = FakeSessionStore(existing)
        val api = FakeAuthApi()
        val attribution = FakeAttribution()
        val manager = AchatSessionManager(store, api, attribution = attribution)

        assertEquals(existing, manager.session())

        assertEquals(0, api.loginCount.get())
        assertEquals(listOf(existing), attribution.reportedSessions)
    }

    @Test
    fun `concurrent first login failure is shared without request storm`() = runBlocking {
        val store = FakeSessionStore(null)
        val api = FakeAuthApi(loginResult = Result.failure(IllegalStateException("offline")))
        val manager = AchatSessionManager(store, api)

        val calls = List(2) {
            async(Dispatchers.Default) {
                runCatching { manager.authenticated { token -> token } }.exceptionOrNull()?.message
            }
        }

        assertEquals(listOf("offline", "offline"), calls.map { it.await() })
        assertEquals(1, api.loginCount.get())
    }

    @Test
    fun `concurrent recovery failure is shared without refresh storm`() = runBlocking {
        val store = FakeSessionStore(session("old", "invalid"))
        val api = FakeAuthApi(
            refreshResult = Result.failure(AchatBackendHttpException(401, "invalid")),
            loginResult = Result.failure(IllegalStateException("offline")),
        )
        val manager = AchatSessionManager(store, api)
        val oldTokenArrivals = CountDownLatch(2)

        val calls = List(2) {
            async(Dispatchers.Default) {
                runCatching {
                    manager.authenticated { token ->
                        if (token == "old") {
                            oldTokenArrivals.countDown()
                            check(oldTokenArrivals.await(5, TimeUnit.SECONDS))
                            throw AchatBackendHttpException(401, "expired")
                        }
                        token
                    }
                }.exceptionOrNull()?.message
            }
        }

        assertEquals(listOf("offline", "offline"), calls.map { it.await() })
        assertEquals(1, api.refreshCount.get())
        assertEquals(1, api.loginCount.get())
    }

    @Test
    fun `failed login can retry after cooldown`() = runBlocking {
        var now = 1_000L
        val store = FakeSessionStore(null)
        val api = FakeAuthApi(loginResult = Result.failure(IllegalStateException("offline")))
        val manager = AchatSessionManager(store, api, nowMillis = { now })

        repeat(2) {
            runCatching { manager.authenticated { token -> token } }
        }
        assertEquals(1, api.loginCount.get())

        now += 1_001L
        runCatching { manager.authenticated { token -> token } }
        assertEquals(2, api.loginCount.get())
    }

    @Test
    fun `failed recovery can retry after cooldown`() = runBlocking {
        var now = 1_000L
        val store = FakeSessionStore(session("old", "invalid"))
        val api = FakeAuthApi(
            refreshResult = Result.failure(AchatBackendHttpException(401, "invalid")),
            loginResult = Result.failure(IllegalStateException("offline")),
        )
        val manager = AchatSessionManager(store, api, nowMillis = { now })

        repeat(2) {
            runCatching {
                manager.authenticated<String> { throw AchatBackendHttpException(401, "expired") }
            }
        }
        assertEquals(1, api.refreshCount.get())
        assertEquals(1, api.loginCount.get())

        now += 1_001L
        runCatching {
            manager.authenticated<String> { throw AchatBackendHttpException(401, "expired") }
        }
        assertEquals(2, api.refreshCount.get())
        assertEquals(2, api.loginCount.get())
    }

    @Test
    fun `non 401 error passes through without refresh`() = runBlocking {
        val store = FakeSessionStore(session("access", "refresh"))
        val api = FakeAuthApi()
        val manager = AchatSessionManager(store, api)
        val expected = AchatBackendHttpException(503, "unavailable")

        val actual = assertThrows(AchatBackendHttpException::class.java) {
            runBlocking { manager.authenticated<String> { throw expected } }
        }

        assertSame(expected, actual)
        assertEquals(0, api.refreshCount.get())
    }

    @Test
    fun `authenticated request retries at most once`() = runBlocking {
        val store = FakeSessionStore(session("old", "refresh"))
        val api = FakeAuthApi(refreshResult = Result.success("new"))
        val manager = AchatSessionManager(store, api)
        var attempts = 0

        assertThrows(AchatBackendHttpException::class.java) {
            runBlocking {
                manager.authenticated<String> {
                    attempts += 1
                    throw AchatBackendHttpException(401, "still unauthorized")
                }
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, api.refreshCount.get())
    }

    private fun session(access: String, refresh: String) = AuthSession(
        userId = "user-1",
        token = access,
        refreshToken = refresh,
        sessionId = "session-1",
        isAnonymous = true,
    )

    private class FakeSessionStore(initial: AuthSession?) : AuthSessionStore {
        var session: AuthSession? = initial
        override fun deviceId(): String = "stable-device"
        override fun readSession(): AuthSession? = session
        override fun saveSession(session: AuthSession) {
            this.session = session
        }
    }

    private class FakeAuthApi(
        private val refreshResult: Result<String> = Result.failure(IllegalStateException("unused")),
        private val loginResult: Result<AuthSession> = Result.failure(IllegalStateException("unused")),
    ) : AchatAuthApi {
        val refreshCount = AtomicInteger()
        val loginCount = AtomicInteger()
        var lastLoginDeviceId: String? = null
        var lastLoginAttribution: LoginAttribution? = null

        override fun refreshAccessToken(refreshToken: String): String {
            refreshCount.incrementAndGet()
            return refreshResult.getOrThrow()
        }

        override fun loginAnonymously(deviceId: String, attribution: LoginAttribution): AuthSession {
            loginCount.incrementAndGet()
            lastLoginDeviceId = deviceId
            lastLoginAttribution = attribution
            return loginResult.getOrThrow()
        }
    }

    private class FakeAttribution : BackendAttribution {
        val loginDeviceIds = mutableListOf<String>()
        val reportedSessions = mutableListOf<AuthSession>()
        override suspend fun forLogin(installDeviceId: String): LoginAttribution {
            loginDeviceIds += installDeviceId
            return loginAttribution()
        }
        override fun currentId(): String = "af-test-id"
        override suspend fun report(session: AuthSession) {
            reportedSessions += session
        }
    }

    private companion object {
        fun loginAttribution() = LoginAttribution(
            attributionSource = "appsflyer",
            deviceId = "gaid-or-install",
            afUid = "af-test-id",
            network = "network-1",
            campaign = null,
            campaignId = null,
            adgroup = null,
            adgroupId = null,
            creative = null,
            creativeId = null,
            channel = null,
            country = null,
            platform = "android",
            appVersion = "1",
            packageName = "test.package",
            extraData = JsonObject(emptyMap()),
        )
    }
}
