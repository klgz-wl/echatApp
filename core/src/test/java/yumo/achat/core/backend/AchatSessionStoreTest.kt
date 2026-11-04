package yumo.achat.core.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.attribution.AttributionLedger
import yumo.achat.core.attribution.MemoryPreferences
import yumo.achat.core.auth.InstallationPreferences
import yumo.achat.core.auth.Session

class AchatSessionStoreTest {
    @Test fun `migrated callback owner publishes authoritative host user with its device instead of stale core device`() = runBlocking {
        val preferences = InstallationPreferences(MemoryPreferences())
        preferences.edit {
            it[InstallationPreferences.DEVICE] = "core-device"
            it[InstallationPreferences.SESSION] = """{"userId":"A","token":"old","refreshToken":"r","epoch":"A"}"""
        }
        val store = AchatSessionStore(preferences) { error("Must retain authoritative persisted host device") }
        val host = yumo.achat.core.auth.LegacyInstallationData(deviceId = "host-device", hostStatePresent = true,
            session = """{"userId":"B","token":"active","refreshToken":"r","epoch":"B"}""")
        preferences.edit { prefs ->
            val migrated = yumo.achat.core.auth.migrateInstallationPreferences(prefs, host)
            prefs[InstallationPreferences.DEVICE] = checkNotNull(migrated[InstallationPreferences.DEVICE])
            prefs[InstallationPreferences.SESSION] = checkNotNull(migrated[InstallationPreferences.SESSION])
        }
        assertEquals("B", store.ownerSnapshot()?.userId)
        assertEquals("host-device", store.ownerSnapshot()?.deviceId)
        assertEquals("host-device", store.deviceId())
        assertEquals("B", store.readSession()?.userId)
    }

    @Test fun `callback owner is one published snapshot shared by adapters and changes only after commit`() = runBlocking {
        val data = MemoryPreferences()
        val preferences = InstallationPreferences(data)
        val store = AchatSessionStore(preferences) { "android" }
        assertEquals("android", store.initializeCallbackOwner().deviceId)
        assertNull(store.ownerSnapshot()!!.userId)
        val session = AuthSession("A", "t", "r", "s", false, "e")
        val other = AchatSessionStore(InstallationPreferences(data)) { "unused" }
        other.saveSession(session)
        assertEquals("A", store.ownerSnapshot()!!.userId)
        val frozen = store.ownerSnapshot()!!
        data.failWrites = true
        assertThrows(IllegalStateException::class.java) { other.saveSession(session.copy(userId = "B")) }
        assertEquals(frozen, store.ownerSnapshot())
        data.failWrites = false
        other.clearSession()
        assertNull(store.ownerSnapshot()!!.userId)
        assertEquals("A", frozen.userId)
    }
    @org.junit.Test fun `late payment refresh cannot restore a logged out host`() = kotlinx.coroutines.runBlocking {
        val storage = yumo.achat.core.auth.InstallationPreferences(yumo.achat.core.attribution.MemoryPreferences())
        val store = AchatSessionStore(storage) { "android-device" }
        store.saveSession(AuthSession("user", "old-token", "refresh", "session", true, "host-epoch"))
        val payment = store.readCoreSession()!!
        store.clearSession()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.writeCoreSession(payment.copy(token = "new-token"), "old-token", preserveHostOnClear = true)
            }
        }
        org.junit.Assert.assertNull(store.readSession())
    }

    private val uuid = "12345678-1234-4234-8234-123456789abc"

    @Test fun `late same user refresh cannot overwrite a new login with repeated token`() = runBlocking {
        val store = AchatSessionStore(InstallationPreferences(MemoryPreferences())) { "android" }
        store.saveSession(AuthSession("user", "token", "refresh", "server", true, "old-host"))
        val oldPayment = store.readCoreSession()!!
        store.clearSession()
        store.saveSession(AuthSession("user", "token", "refresh", "server", true, "new-host"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.writeCoreSession(oldPayment.copy(token = "late-refresh"), "token", true) }
        }
        assertEquals("new-host", store.readSession()?.epoch)
        assertEquals("token", store.readSession()?.token)
    }

    @Test fun `anonymous preparation freezes all owners before replacing UUID`() = runBlocking {
        val preferences = InstallationPreferences(MemoryPreferences())
        preferences.edit {
            it[InstallationPreferences.DEVICE] = uuid
            it[InstallationPreferences.LEDGER] = """{"reports":[{"reportId":"a","resultId":"r","userId":"a"},{"reportId":"b","resultId":"r","userId":"b","succeeded":true}]}"""
        }
        val store = AchatSessionStore(preferences) { "android" }
        assertEquals(uuid, store.deviceId())
        assertEquals("android", store.prepareAnonymousDeviceId())
        val saved = preferences.read()
        assertEquals(listOf(uuid, uuid), AttributionLedger.decode(saved[InstallationPreferences.LEDGER]).reports.map { it.deviceId })
    }

    @Test fun `existing session blocks UUID migration and refreshed save is durable`() = runBlocking {
        val preferences = InstallationPreferences(MemoryPreferences())
        preferences.edit { it[InstallationPreferences.DEVICE] = uuid }
        val store = AchatSessionStore(preferences) { error("must not migrate") }
        val session = AuthSession("user", "token", "refresh", "server", false, "host")
        store.saveSession(session)
        assertEquals(uuid, store.prepareAnonymousDeviceId())
        assertEquals(session, AchatSessionStore(preferences) { "android" }.readSession())
        assertFalse(store.saveSessionIfTokenUnchanged("stale", session.copy(token = "wrong")))
        assertTrue(store.saveSessionIfTokenUnchanged("token", session.copy(token = "new")))
        assertEquals("new", store.readSession()?.token)
    }

    @Test fun `core adapter preserves host metadata and payment revision in one store`() = runBlocking {
        val preferences = InstallationPreferences(MemoryPreferences())
        val store = AchatSessionStore(preferences) { "android" }
        store.saveSession(AuthSession("user", "token", "refresh", "server", false, "host"))
        val paymentEpoch = store.readCoreSession()!!.epoch
        store.writeCoreSession(Session("next", "refresh", "user", paymentEpoch, 9), "token", true)
        assertEquals("host", store.readSession()?.epoch)
        assertEquals("server", store.readSession()?.sessionId)
        assertFalse(store.readSession()!!.isAnonymous)
        assertEquals(Session("next", "refresh", "user", paymentEpoch, 9), store.readCoreSession())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.writeCoreSession(Session("stale", "r", "user", paymentEpoch, 10), "token", true) }
        }
        store.saveSession(store.readSession()!!.copy(token = "host-refresh"))
        assertEquals(10L, store.readCoreSession()?.revision)
        store.writeCoreSession(null, preserveHostOnClear = true)
        assertNotNull(store.readSession())
        store.clearSession()
        assertNull(store.readSession())
    }

    @Test fun `failed session save never exposes new credentials`() {
        val store = AchatSessionStore(InstallationPreferences(MemoryPreferences(failWrites = true))) { "android" }
        assertThrows(IllegalStateException::class.java) { store.saveSession(AuthSession("u", "t", "r", "s", true)) }
        assertNull(store.readSession())
    }
}
