package yumo.achat.core.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.attribution.LoginAttribution

class AchatSessionManagerTest {
    @Test fun `startup without session never authenticates`() = runBlocking {
        val api = FakeApi()
        assertNull(AchatSessionManager(FakeStore(), api).startupSession())
        assertEquals(0, api.anonymousCalls)
    }

    @Test fun `startup restores saved session without login`() = runBlocking {
        val saved = session("saved")
        val api = FakeApi()
        assertEquals(saved, AchatSessionManager(FakeStore(saved), api).startupSession())
        assertEquals(0, api.anonymousCalls)
    }

    @Test fun `authenticated request with no session stays signed out`() = runBlocking {
        val api = FakeApi()
        assertThrows(SignedOutException::class.java) {
            runBlocking { AchatSessionManager(FakeStore(), api).authenticated { it } }
        }
        assertEquals(0, api.anonymousCalls)
    }

    @Test fun `401 refreshes and retries once`() = runBlocking {
        val store = FakeStore(session("old"))
        val api = FakeApi().apply { refreshed = "new" }
        val tokens = mutableListOf<String>()
        val result = AchatSessionManager(store, api).authenticated { token ->
            tokens += token
            if (token == "old") throw AchatBackendHttpException(401, "expired")
            token
        }
        assertEquals("new", result)
        assertEquals(listOf("old", "new"), tokens)
        assertEquals("new", store.saved?.token)
        assertEquals(1, api.refreshCalls)
    }

    @Test fun `concurrent 401 responses share a single refresh`() = runBlocking {
        val store = FakeStore(session("old"))
        val api = FakeApi().apply { refreshed = "new" }
        val manager = AchatSessionManager(store, api)
        val arrivals = CountDownLatch(2)
        val calls = List(2) {
            async(Dispatchers.Default) {
                manager.authenticated { token ->
                    if (token == "old") {
                        arrivals.countDown()
                        check(arrivals.await(5, TimeUnit.SECONDS))
                        throw AchatBackendHttpException(401, "expired")
                    }
                    token
                }
            }
        }
        assertEquals(listOf("new", "new"), calls.map { it.await() })
        assertEquals(1, api.refreshCalls)
    }

    @Test fun `invalid refresh clears session without anonymous login`() = runBlocking {
        val store = FakeStore(session("old"))
        val api = FakeApi().apply { refreshError = AchatBackendHttpException(401, "invalid") }
        assertThrows(SignedOutException::class.java) {
            runBlocking { AchatSessionManager(store, api).authenticated<String> { throw AchatBackendHttpException(401, "expired") } }
        }
        assertNull(store.saved)
        assertEquals(0, api.anonymousCalls)
    }

    @Test fun `definitively invalid refresh emits signed out state for an active screen`() = runBlocking {
        val store = FakeStore(session("old"))
        val api = FakeApi().apply { refreshError = AchatBackendHttpException(401, "invalid") }
        val manager = AchatSessionManager(store, api)
        val signal = async { manager.sessionInvalidated.first { it } }

        runCatching { manager.authenticated<String> { throw AchatBackendHttpException(401, "expired") } }

        assertEquals(true, signal.await())
    }

    @Test fun `temporary refresh failure preserves session`() = runBlocking {
        val saved = session("old")
        val store = FakeStore(saved)
        val api = FakeApi().apply { refreshError = AchatBackendHttpException(503, "unavailable") }
        val manager = AchatSessionManager(store, api)
        assertThrows(AchatBackendHttpException::class.java) {
            runBlocking { manager.authenticated<String> { throw AchatBackendHttpException(401, "expired") } }
        }
        assertEquals(saved, store.saved)
        assertEquals(0, api.anonymousCalls)
        assertEquals(false, manager.sessionInvalidated.value)
    }

    @Test fun `one tap prepares device before attribution and persists only successful login`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi().apply { anonymousResult = session("anonymous") }
        val attribution = FakeAttribution()
        val result = AchatSessionManager(store, api, attribution).loginAnonymouslyExplicit()
        assertEquals(result, store.saved)
        assertEquals(listOf("migrated-device"), attribution.deviceIds)
        assertEquals("migrated-device", api.anonymousDeviceId)
    }

    @Test fun `failed one tap leaves device migration committed without session`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi().apply { anonymousError = IllegalStateException("rejected") }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AchatSessionManager(store, api).loginAnonymouslyExplicit() }
        }
        assertNull(store.saved)
        assertEquals(true, store.prepared)
    }

    @Test fun `register uses shared device and persists successful account session`() = runBlocking {
        val store = FakeStore()
        val api = FakeApi().apply { registerResult = session("registered").copy(isAnonymous = false) }
        val result = AchatSessionManager(store, api).register("user+1", "secret12")
        assertEquals(result, store.saved)
        assertEquals("stable-device", api.registerDeviceId)
        assertEquals("user+1", api.registerUsername)
    }

    @Test fun `failed logout preserves session and successful delete clears it`() = runBlocking {
        val saved = session("access")
        val store = FakeStore(saved)
        val api = FakeApi().apply { logoutError = IllegalStateException("rejected") }
        val manager = AchatSessionManager(store, api)
        assertThrows(IllegalStateException::class.java) { runBlocking { manager.logout() } }
        assertEquals(saved, store.saved)
        manager.logout(deleteAccount = true)
        assertNull(store.saved)
        assertEquals("access", api.deleteToken)
    }

    @Test fun `logout and delete refresh expired access then retry once`() = runBlocking {
        for (delete in listOf(false, true)) {
            val store = FakeStore(session("expired"))
            val api = FakeApi().apply {
                refreshed = "fresh"
                actionFailure = { token -> if (token == "expired") AchatBackendHttpException(401, "expired") else null }
            }
            val manager = AchatSessionManager(store, api)
            manager.logout(deleteAccount = delete)

            assertEquals(listOf("expired", "fresh"), api.actionTokens)
            assertEquals(1, api.refreshCalls)
            assertNull(store.saved)
            assertEquals(false, manager.sessionInvalidated.value)
        }
    }

    @Test fun `second 401 after refresh preserves refreshed session`() = runBlocking {
        val store = FakeStore(session("expired"))
        val api = FakeApi().apply {
            refreshed = "fresh"
            actionFailure = { AchatBackendHttpException(401, "unauthorized") }
        }
        val manager = AchatSessionManager(store, api)
        assertThrows(AchatBackendHttpException::class.java) { runBlocking { manager.logout() } }

        assertEquals(listOf("expired", "fresh"), api.actionTokens)
        assertEquals("fresh", store.saved?.token)
        assertEquals(false, manager.sessionInvalidated.value)
    }

    @Test fun `temporary logout refresh failure preserves saved session`() = runBlocking {
        val original = session("expired")
        val store = FakeStore(original)
        val api = FakeApi().apply {
            actionFailure = { AchatBackendHttpException(401, "expired") }
            refreshError = AchatBackendHttpException(503, "temporary")
        }
        assertThrows(AchatBackendHttpException::class.java) {
            runBlocking { AchatSessionManager(store, api).logout() }
        }
        assertEquals(original, store.saved)
        assertEquals(listOf("expired"), api.actionTokens)
    }

    @Test fun `invalid refresh during delete signs out without anonymous fallback`() = runBlocking {
        val store = FakeStore(session("expired"))
        val api = FakeApi().apply {
            actionFailure = { AchatBackendHttpException(401, "expired") }
            refreshError = AchatBackendHttpException(401, "invalid refresh")
        }
        assertThrows(SignedOutException::class.java) {
            runBlocking { AchatSessionManager(store, api).logout(deleteAccount = true) }
        }
        assertNull(store.saved)
        assertEquals(0, api.anonymousCalls)
    }

    private fun session(token: String) = AuthSession("user", token, "refresh", "session", true)

    private class FakeStore(var saved: AuthSession? = null) : AuthSessionStore {
        var prepared = false
        override fun deviceId() = "stable-device"
        override fun prepareAnonymousDeviceId(): String { prepared = true; return "migrated-device" }
        override fun readSession() = saved
        override fun saveSession(session: AuthSession) { saved = session }
        override fun clearSession() { saved = null }
    }

    private class FakeApi : AchatAuthApi {
        var anonymousCalls = 0
        var refreshCalls = 0
        var anonymousResult: AuthSession = AuthSession("user", "anonymous", "refresh", "session", true)
        var registerResult: AuthSession = AuthSession("user", "registered", "refresh", "session", false)
        var refreshed = "refreshed"
        var refreshError: Exception? = null
        var logoutError: Exception? = null
        var anonymousError: Exception? = null
        var anonymousDeviceId: String? = null
        var registerDeviceId: String? = null
        var registerUsername: String? = null
        var deleteToken: String? = null
        val actionTokens = mutableListOf<String>()
        var actionFailure: ((String) -> Exception?)? = null
        override fun loginAnonymously(deviceId: String, attribution: LoginAttribution): AuthSession {
            anonymousCalls++
            anonymousDeviceId = deviceId
            anonymousError?.let { throw it }
            return anonymousResult
        }
        override fun register(username: String, password: String, deviceId: String, attribution: LoginAttribution): AuthSession {
            registerDeviceId = deviceId
            registerUsername = username
            return registerResult
        }
        override fun refreshAccessToken(refreshToken: String): String {
            refreshCalls++
            refreshError?.let { throw it }
            return refreshed
        }
        override fun logout(token: String) {
            actionTokens += token
            logoutError?.let { throw it }
            actionFailure?.invoke(token)?.let { throw it }
        }
        override fun deleteAccount(token: String) {
            actionTokens += token
            actionFailure?.invoke(token)?.let { throw it }
            deleteToken = token
        }
    }

    private class FakeAttribution : BackendAttribution {
        val deviceIds = mutableListOf<String>()
        override suspend fun forLogin(installDeviceId: String): LoginAttribution {
            deviceIds += installDeviceId
            return LoginAttribution(
                attributionSource = "none", deviceId = installDeviceId, afUid = "",
                network = null, campaign = null, campaignId = null, adgroup = null, adgroupId = null,
                creative = null, creativeId = null, channel = null, country = null,
                platform = "android", appVersion = "1", packageName = "package", extraData = JsonObject(emptyMap()),
            )
        }
        override fun currentId() = ""
        override suspend fun report(session: AuthSession) = Unit
    }
}
