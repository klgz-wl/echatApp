package com.zorv.core.auth

import com.zorv.core.attribution.AttributionCoordinator
import com.zorv.core.config.AppMode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

fun interface NetworkAvailability { fun available(): Boolean }
class StartupConfiguration(val timeoutMillis: Long) { init { require(timeoutMillis > 0) } }
class StartupOfflineException : Exception()
class StartupUnavailableException : Exception()

/** 启动只有匿名认证入口，登录后资料决定 A/B，认证失败由 Splash 保留重试。 */
class AnonymousStartup @Inject constructor(private val auth: AuthRepository, private val sessions: SessionCoordinator,
    private val attribution: AttributionCoordinator, private val profiles: UserProfileRepository, private val network: NetworkAvailability, private val config: StartupConfiguration) {
    suspend fun start(): AppMode {
        try {
            return withTimeout(config.timeoutMillis) {
                if (!network.available()) throw StartupOfflineException()
                attribution.awaitInitial()
                if (!network.available()) throw StartupOfflineException()
                sessions.restore()
                auth.anonymous()
                if (sessions.current == null) throw StartupUnavailableException()
                profiles.refresh().mode
            }
        } catch (_: TimeoutCancellationException) { throw StartupUnavailableException() }
    }
}
