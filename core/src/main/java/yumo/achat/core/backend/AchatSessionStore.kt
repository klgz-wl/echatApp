package yumo.achat.core.backend

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

internal interface AuthSessionStore {
    fun deviceId(): String
    fun readSession(): AuthSession?
    fun saveSession(session: AuthSession)
    fun clearSession()
    fun prepareAnonymousDeviceId(): String = deviceId()
}

class AchatSessionStore(context: Context) : AuthSessionStore {
    private val preferences = context.getSharedPreferences("achat_backend_session", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    private fun androidId(): String = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
        ?.takeIf(String::isNotBlank) ?: error("Android ID unavailable")

    override fun deviceId(): String {
        return StableInstallId.resolve(
            read = { preferences.getString(KEY_DEVICE_ID, null) },
            persist = { preferences.edit().putString(KEY_DEVICE_ID, it).commit() },
            generate = ::androidId,
        )
    }

    override fun prepareAnonymousDeviceId(): String = synchronized(SESSION_LOCK) {
        val current = deviceId()
        if (readSession() != null || runCatching { UUID.fromString(current) }.isFailure) return@synchronized current
        val replacement = androidId()
        check(preferences.edit().putString(KEY_DEVICE_ID, replacement).commit()) { "Unable to persist Android ID" }
        replacement
    }

    override fun readSession(): AuthSession? = synchronized(SESSION_LOCK) {
        val stored = preferences.getString(KEY_AUTH_SESSION, null) ?: return null
        runCatching {
            val json = JSONObject(stored)
            AuthSession(
                userId = json.getString("userId"),
                token = json.getString("token"),
                refreshToken = json.getString("refreshToken"),
                sessionId = json.getString("sessionId"),
                isAnonymous = json.optBoolean("isAnonymous", true),
                epoch = json.optString("epoch").takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
            )
        }.getOrNull()
    }

    override fun saveSession(session: AuthSession) = synchronized(SESSION_LOCK) {
        saveSessionLocked(session)
    }

    fun saveSessionIfTokenUnchanged(expectedToken: String?, session: AuthSession): Boolean = synchronized(SESSION_LOCK) {
        val currentToken = readSession()?.token
        if (currentToken != expectedToken) return@synchronized false
        saveSessionLocked(session)
        true
    }

    private fun saveSessionLocked(session: AuthSession) {
        val json = JSONObject()
            .put("userId", session.userId)
            .put("token", session.token)
            .put("refreshToken", session.refreshToken)
            .put("sessionId", session.sessionId)
            .put("isAnonymous", session.isAnonymous)
            .put("epoch", session.epoch)
        preferences.edit().putString(KEY_AUTH_SESSION, json.toString()).apply()
    }

    override fun clearSession() = synchronized(SESSION_LOCK) {
        check(preferences.edit()
            .remove(KEY_AUTH_SESSION)
            .remove("core_payment_epoch")
            .remove("core_payment_revision")
            .remove("core_payment_user")
            .commit()) { "Unable to clear session" }
        Unit
    }

    private companion object {
        val SESSION_LOCK = Any()
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_AUTH_SESSION = "auth_session"
    }
}
