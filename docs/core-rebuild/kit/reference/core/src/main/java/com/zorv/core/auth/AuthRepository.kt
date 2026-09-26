package com.zorv.core.auth

import com.zorv.core.config.AppConfiguration
import com.zorv.core.config.ClientIdentity
import com.zorv.core.network.*
import com.zorv.core.attribution.LoginAttributionProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

fun interface UserDataCleaner { suspend fun clear(userId: String) }

interface AuthRepository {
    suspend fun login(account: String, password: String)
    suspend fun anonymous()
    suspend fun google(idToken: String)
    suspend fun logout(delete: Boolean = false)
}
@Singleton
class ApiAuthRepository @Inject constructor(private val api: PublicAuthApi, private val accountApi: AccountApi,
    private val sessions: SessionCoordinator, private val storage: SessionStorage,
    private val identity: ClientIdentity, private val config: AppConfiguration, private val attribution: LoginAttributionProvider,
    private val cleaner: UserDataCleaner) : AuthRepository {
    private val operation = Mutex()
    override suspend fun login(account: String, password: String) = operation.withLock {
        if (!config.accountRules.accepts(account, password)) throw ServiceFailure.InvalidAccount
        val response = api.login(LoginRequest(account.trim(), password, storage.deviceId(), identity.packageName, "android", identity.versionName, attribution.forLogin())).requireData()
        sessions.saveLogin(response)
    }
    override suspend fun anonymous() = operation.withLock {
        val response = api.anonymous(AnonymousRequest(storage.deviceId(), identity.packageName, "android", identity.versionName, attribution.forLogin())).requireData()
        sessions.saveLogin(response)
    }
    override suspend fun google(idToken: String) = operation.withLock {
        if (idToken.isBlank()) throw ServiceFailure.InvalidResponse
        val response = api.google(GoogleRequest(idToken, storage.deviceId(), identity.packageName, "android", identity.versionName, attribution.forLogin())).requireData()
        sessions.saveLogin(response)
    }
    override suspend fun logout(delete: Boolean) = operation.withLock {
        val expected = sessions.current ?: throw ServiceFailure.SignedOut
        (if (delete) accountApi.deleteAccount(expected) else accountApi.logout(expected)).checkSuccess()
        if (sessions.current?.epoch != expected.epoch) throw ServiceFailure.Superseded
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try { cleaner.clear(expected.userId) }
            finally { sessions.clear(expected) }
        }
    }
}
