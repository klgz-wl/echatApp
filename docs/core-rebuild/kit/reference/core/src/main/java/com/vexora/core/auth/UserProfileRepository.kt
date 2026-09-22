package com.vexora.core.auth

import com.vexora.core.config.AppMode
import com.vexora.core.network.ApiResponse
import com.vexora.core.network.ServiceFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Tag
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class UserProfile(@SerialName("is_new") val isNew: Boolean) {
    val mode: AppMode get() = if (isNew) AppMode.B else AppMode.A
}

interface UserProfileApi {
    @GET("user/profile") suspend fun profile(@Tag session: Session): ApiResponse<UserProfile>
}

data class ProfileMode(val epoch: String, val mode: AppMode)

/** 首次登录和归因补报共用串行刷新，旧账号响应不能覆盖新会话的模式。 */
@Singleton
class UserProfileRepository @Inject constructor(private val api: UserProfileApi, private val sessions: SessionCoordinator) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<ProfileMode?>(null)
    val state = mutable.asStateFlow()

    suspend fun refresh(expectedEpoch: String? = sessions.current?.epoch): ProfileMode = lock.withLock {
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        if (session.epoch != expectedEpoch) throw ServiceFailure.Superseded
        val profile = api.profile(session).requireData()
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        ProfileMode(session.epoch, profile.mode).also { mutable.value = it }
    }
}
