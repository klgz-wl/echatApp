package com.vexora.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.app.analytics.AnalyticsHub
import com.zorv.core.auth.*
import com.zorv.core.config.AppConfiguration
import com.zorv.core.config.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** 正式入口仅匿名登录；历史账号输入字段只供旧页面组件编译，不参与启动。 */
data class AuthUiState(val restoring: Boolean = true, val busy: Boolean = false, val mode: AppMode? = null,
    val userId: String? = null, val sessionEpoch: String? = null, val account: String = "", val password: String = "",
    val error: Throwable? = null) {
    override fun toString() = "AuthUiState(凭据已隐藏)"
}

@HiltViewModel
class AuthViewModel @Inject constructor(private val sessions: SessionCoordinator,
    val config: AppConfiguration, private val analytics: AnalyticsHub, private val network: NetworkAvailability,
    private val startup: AnonymousStartup, private val profiles: UserProfileRepository, private val events: com.vexora.app.analytics.BusinessEventQueue) : ViewModel() {
    private val mutable = MutableStateFlow(AuthUiState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            combine(profiles.state, sessions.state) { profile, session -> profile?.takeIf { it.epoch == session?.epoch } }
                .collect { profile ->
                    events.mode = profile?.mode?.name ?: "unknown"
                    mutable.update { it.copy(mode = profile?.mode) }
                }
        }
        viewModelScope.launch {
            sessions.state.collect { session ->
                mutable.update { it.copy(userId = session?.userId, sessionEpoch = session?.epoch) }
                if (session == null && !state.value.restoring && !state.value.busy) restore()
            }
        }
    }
    /** 首帧展示 Splash 后由页面调用；失败只在用户点击 Retry 后重新尝试。 */
    fun start() { if (state.value.restoring && state.value.error == null) restore() }
    fun restore() {
        if (state.value.busy) return
        mutable.update { it.copy(restoring = true, busy = true, error = null) }
        viewModelScope.launch {
            try {
                analytics.initialize()
                startup.start()
                val mode = profiles.state.value?.takeIf { it.epoch == sessions.current?.epoch }?.mode
                    ?: throw StartupUnavailableException()
                events.mode = mode.name
                events.track("silent_login", mapOf("is_success" to true, "device_model" to android.os.Build.MODEL,
                    "os_version" to android.os.Build.VERSION.RELEASE))
                mutable.update { it.copy(restoring = false, busy = false, mode = mode, userId = sessions.current?.userId,
                    sessionEpoch = sessions.current?.epoch) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                events.track("silent_login", mapOf("is_success" to false, "device_model" to android.os.Build.MODEL,
                    "os_version" to android.os.Build.VERSION.RELEASE, "fail_reason" to if (!network.available()) "offline" else "authentication_unavailable"))
                mutable.update { it.copy(busy = false, error = if (!network.available()) StartupOfflineException() else error) }
            }
        }
    }
}
