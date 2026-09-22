package com.vexora.core.auth

import com.vexora.core.network.AuthResponse
import com.vexora.core.network.ServiceFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Session(val token: String, val refreshToken: String, val userId: String, val epoch: String, val revision: Long = 0) {
    override fun toString() = "Session(凭据已隐藏)"
}
interface SessionStorage {
    suspend fun read(): Session?
    suspend fun write(session: Session?)
    suspend fun deviceId(): String
}

/** 同步刷新只使用专用同步 HTTP 客户端，防止 OkHttp 协程调度池被并发 401 占满。 */
@Singleton
class SessionCoordinator @Inject constructor(private val storage: SessionStorage) {
    private val guard = Any()
    private var restored = false
    private val mutable = MutableStateFlow<Session?>(null)
    val state = mutable.asStateFlow()
    val current: Session? get() = mutable.value

    suspend fun restore() = withContext(Dispatchers.IO) {
        synchronized(guard) {
            if (!restored) {
                mutable.value = runBlocking { storage.read() }
                restored = true
            }
        }
    }
    suspend fun saveLogin(response: AuthResponse) = withContext(Dispatchers.IO) {
        val refresh = response.refreshToken?.takeIf { it.isNotBlank() } ?: throw ServiceFailure.InvalidResponse
        val user = response.userId?.takeIf { it.isNotBlank() } ?: throw ServiceFailure.InvalidResponse
        if (response.token.isBlank()) throw ServiceFailure.InvalidResponse
        synchronized(guard) {
            publish(Session(response.token, refresh, user, UUID.randomUUID().toString()))
        }
    }
    suspend fun clear(expected: Session) = withContext(Dispatchers.IO) {
        synchronized(guard) { if (current?.epoch == expected.epoch) publish(null) }
    }

    fun refresh(expected: Session, fetch: (String) -> AuthResponse): Session? = synchronized(guard) {
        val active = current ?: return@synchronized null
        if (active.epoch != expected.epoch) return@synchronized null
        if (active.revision != expected.revision) return@synchronized active
        val response = fetch(active.refreshToken)
        if (response.token.isBlank() || (response.userId != null && response.userId != active.userId))
            throw ServiceFailure.InvalidResponse
        val updated = active.copy(token = response.token, refreshToken = response.refreshToken?.takeIf { it.isNotBlank() } ?: active.refreshToken, revision = active.revision + 1)
        publish(updated)
        updated
    }
    fun invalidate(expected: Session) = synchronized(guard) {
        if (current?.epoch == expected.epoch && current?.revision == expected.revision) publish(null)
    }
    private fun publish(session: Session?) {
        runBlocking { storage.write(session) }
        mutable.value = session
        restored = true
    }
}
