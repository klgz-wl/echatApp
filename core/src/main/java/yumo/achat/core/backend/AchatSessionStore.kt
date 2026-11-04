package yumo.achat.core.backend

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import yumo.achat.core.auth.InstallationPreferences
import yumo.achat.core.auth.InstallationOwner
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.replaceLegacyDeviceId
import java.util.UUID

internal interface AuthSessionStore {
    fun deviceId(): String
    fun readSession(): AuthSession?
    fun saveSession(session: AuthSession)
    fun clearSession()
    fun prepareAnonymousDeviceId(): String = deviceId()
}

class AchatSessionStore(
    private val installation: InstallationPreferences,
    private val androidId: () -> String,
) : AuthSessionStore {
    constructor(context: Context) : this(InstallationPreferences(context), {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf(String::isNotBlank) ?: error("Android ID unavailable")
    })

    fun initializeCallbackOwner(): InstallationOwner {
        deviceId()
        return checkNotNull(ownerSnapshot())
    }

    fun ownerSnapshot(): InstallationOwner? = installation.ownerSnapshot()

    override fun deviceId(): String = blocking {
        val existing = installation.read()[InstallationPreferences.DEVICE]
        if (existing != null) return@blocking existing.also { check(it.isNotBlank()) { "Invalid stored installation identity" } }
        val generated = androidId().also { require(it.isNotBlank()) }
        installation.edit { if (it[InstallationPreferences.DEVICE] == null) it[InstallationPreferences.DEVICE] = generated }[InstallationPreferences.DEVICE]!!
    }

    override fun prepareAnonymousDeviceId(): String {
        deviceId()
        return blocking {
            val saved = installation.edit { prefs ->
                val current = checkNotNull(prefs[InstallationPreferences.DEVICE])
                if (prefs[InstallationPreferences.SESSION] == null && isUuid(current)) replaceLegacyDeviceId(prefs, androidId())
            }
            checkNotNull(saved[InstallationPreferences.DEVICE])
        }
    }

    override fun readSession(): AuthSession? = blocking {
        val raw = installation.read()[InstallationPreferences.SESSION] ?: return@blocking null
        val json = Json.parseToJsonElement(raw).jsonObject
        if (!json["epoch"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return@blocking decodeSession(json)
        val repaired = installation.edit { prefs ->
            prefs[InstallationPreferences.SESSION]?.let { current ->
                val value = Json.parseToJsonElement(current).jsonObject
                if (value["epoch"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                    prefs[InstallationPreferences.SESSION] = JsonObject(value + ("epoch" to JsonPrimitive(UUID.randomUUID().toString()))).toString()
                }
            }
        }
        repaired[InstallationPreferences.SESSION]?.let { decodeSession(Json.parseToJsonElement(it).jsonObject) }
    }

    override fun saveSession(session: AuthSession) { blocking { installation.edit { save(it, session) } } }

    fun saveSessionIfTokenUnchanged(expectedToken: String?, session: AuthSession): Boolean = blocking {
        var accepted = false
        installation.edit { prefs ->
            val previous = prefs[InstallationPreferences.SESSION]?.let { decodeSession(Json.parseToJsonElement(it).jsonObject) }
            if (previous?.token == expectedToken) {
                save(prefs, session)
                accepted = true
            }
        }
        accepted
    }

    override fun clearSession() { blocking {
        installation.edit { it.remove(InstallationPreferences.SESSION); clearPaymentMetadata(it) }
    } }

    internal suspend fun readCoreSession(): Session? {
        // Repair a pre-epoch host session durably before exposing it to either adapter.
        readSession() ?: return null
        val prefs = installation.edit { prefs ->
            val raw = prefs[InstallationPreferences.SESSION]?.let { Json.parseToJsonElement(it).jsonObject }
            val user = raw?.get("userId")?.jsonPrimitive?.contentOrNull
            if (user != null && (prefs[InstallationPreferences.PAYMENT_USER] != user || prefs[InstallationPreferences.PAYMENT_EPOCH].isNullOrBlank())) {
                prefs[InstallationPreferences.PAYMENT_USER] = user
                prefs[InstallationPreferences.PAYMENT_EPOCH] = UUID.randomUUID().toString()
                prefs[InstallationPreferences.PAYMENT_REVISION] = revision(checkNotNull(raw))
            }
        }
        val raw = prefs[InstallationPreferences.SESSION]?.let { Json.parseToJsonElement(it).jsonObject } ?: return null
        val saved = decodeSession(raw)
        val sameUser = prefs[InstallationPreferences.PAYMENT_USER] == saved.userId
        return Session(saved.token, saved.refreshToken, saved.userId,
            if (sameUser) prefs[InstallationPreferences.PAYMENT_EPOCH] ?: saved.epoch else saved.epoch,
            if (sameUser) prefs[InstallationPreferences.PAYMENT_REVISION] ?: revision(raw) else revision(raw))
    }

    internal suspend fun writeCoreSession(session: Session?, expectedToken: String? = null, preserveHostOnClear: Boolean = false) {
        installation.edit { prefs ->
            if (session == null) {
                if (!preserveHostOnClear) prefs.remove(InstallationPreferences.SESSION)
                clearPaymentMetadata(prefs)
            } else {
                val raw = prefs[InstallationPreferences.SESSION]?.let { Json.parseToJsonElement(it).jsonObject }
                val old = raw?.let(::decodeSession)
                check(!preserveHostOnClear || old != null) { "Host session ended during Core refresh" }
                check(!preserveHostOnClear || (prefs[InstallationPreferences.PAYMENT_USER] == session.userId &&
                    prefs[InstallationPreferences.PAYMENT_EPOCH] == session.epoch)) { "Core session owner changed during refresh" }
                check(!preserveHostOnClear || old == null || old.userId == session.userId) { "Host session owner changed" }
                check(!preserveHostOnClear || old == null || expectedToken == null || old.token == expectedToken || old.token == session.token) { "Host session changed while Core refreshed" }
                val sameOwner = old?.takeIf { it.userId == session.userId }
                val updated = AuthSession(session.userId, session.token, session.refreshToken,
                    sameOwner?.sessionId.orEmpty(), sameOwner?.isAnonymous ?: true,
                    if (preserveHostOnClear) old?.epoch ?: session.epoch else session.epoch)
                prefs[InstallationPreferences.SESSION] = encodeSession(updated, session.revision).toString()
                prefs[InstallationPreferences.PAYMENT_EPOCH] = session.epoch
                prefs[InstallationPreferences.PAYMENT_REVISION] = session.revision
                prefs[InstallationPreferences.PAYMENT_USER] = session.userId
            }
        }
    }

    private fun save(prefs: MutablePreferences, session: AuthSession) {
        val raw = prefs[InstallationPreferences.SESSION]?.let { Json.parseToJsonElement(it).jsonObject }
        val old = raw?.let(::decodeSession)
        val sameSession = old != null && old.userId == session.userId && old.epoch == session.epoch
        val previousRevision = maxOf(raw?.let(::revision) ?: 0,
            if (prefs[InstallationPreferences.PAYMENT_USER] == session.userId) prefs[InstallationPreferences.PAYMENT_REVISION] ?: 0 else 0)
        val revision = if (sameSession) previousRevision + if (old?.token != session.token) 1 else 0 else 0
        if (!sameSession) clearPaymentMetadata(prefs)
        else if (prefs[InstallationPreferences.PAYMENT_USER] == session.userId) prefs[InstallationPreferences.PAYMENT_REVISION] = revision
        prefs[InstallationPreferences.SESSION] = encodeSession(session, revision).toString()
    }

    private fun clearPaymentMetadata(prefs: MutablePreferences) {
        prefs.remove(InstallationPreferences.PAYMENT_EPOCH)
        prefs.remove(InstallationPreferences.PAYMENT_REVISION)
        prefs.remove(InstallationPreferences.PAYMENT_USER)
    }
    private fun revision(raw: JsonObject): Long = raw["revision"]?.jsonPrimitive?.longOrNull ?: 0
    private fun decodeSession(raw: JsonObject): AuthSession {
        fun required(key: String) = raw[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Invalid stored session")
        return AuthSession(required("userId"), required("token"), required("refreshToken"),
            raw["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty(), raw["isAnonymous"]?.jsonPrimitive?.booleanOrNull ?: true,
            required("epoch"))
    }
    private fun encodeSession(session: AuthSession, revision: Long) = buildJsonObject {
        put("userId", session.userId); put("token", session.token); put("refreshToken", session.refreshToken)
        put("sessionId", session.sessionId); put("isAnonymous", session.isAnonymous); put("epoch", session.epoch); put("revision", revision)
    }
    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)
    private fun <T> blocking(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}
