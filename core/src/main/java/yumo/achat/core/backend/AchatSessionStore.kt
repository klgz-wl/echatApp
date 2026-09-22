package yumo.achat.core.backend

import android.content.Context
import org.json.JSONObject
import java.util.UUID

internal interface AuthSessionStore {
    fun deviceId(): String
    fun readSession(): AuthSession?
    fun saveSession(session: AuthSession)
}

class AchatSessionStore(context: Context) : AuthSessionStore {
    private val preferences = context.getSharedPreferences("achat_backend_session", Context.MODE_PRIVATE)

    override fun deviceId(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        return UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        }
    }

    override fun readSession(): AuthSession? {
        val stored = preferences.getString(KEY_AUTH_SESSION, null) ?: return null
        return runCatching {
            val json = JSONObject(stored)
            AuthSession(
                userId = json.getString("userId"),
                token = json.getString("token"),
                refreshToken = json.getString("refreshToken"),
                sessionId = json.getString("sessionId"),
                isAnonymous = json.optBoolean("isAnonymous", true),
            )
        }.getOrNull()
    }

    override fun saveSession(session: AuthSession) {
        val json = JSONObject()
            .put("userId", session.userId)
            .put("token", session.token)
            .put("refreshToken", session.refreshToken)
            .put("sessionId", session.sessionId)
            .put("isAnonymous", session.isAnonymous)
        preferences.edit().putString(KEY_AUTH_SESSION, json.toString()).apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_AUTH_SESSION = "auth_session"
    }
}
