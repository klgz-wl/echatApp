package com.zorv.core.auth

import com.zorv.core.config.AppMode
import com.zorv.core.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserProfileRepositoryTest {
    @Test fun `资料读取真实布尔字段且缺失字段不能猜测模式`() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        assertEquals(AppMode.B, json.decodeFromString<UserProfile>("""{"is_new":true,"nickname":"name"}""").mode)
        assertEquals(AppMode.A, json.decodeFromString<UserProfile>("""{"is_new":false}""").mode)
        assertTrue(runCatching { json.decodeFromString<UserProfile>("{}") }.isFailure)
        assertTrue(runCatching { json.decodeFromString<UserProfile>("""{"is_new":null}""") }.isFailure)
    }

    @Test fun `归因刷新串行排在首次资料请求后且最终采用最新结果`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val repository = UserProfileRepository(object : UserProfileApi {
            override suspend fun profile(session: Session): ApiResponse<UserProfile> {
                calls++
                if (calls == 1) { entered.complete(Unit); release.await(); return ApiResponse(0, data = UserProfile(false)) }
                return ApiResponse(0, data = UserProfile(true))
            }
        }, sessions)
        val initial = async { repository.refresh() }
        entered.await()
        val late = async { repository.refresh() }
        yield(); assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(AppMode.A, initial.await().mode)
        assertEquals(AppMode.B, late.await().mode)
        assertEquals(AppMode.B, repository.state.value?.mode)
    }

    @Test fun `旧会话迟到响应与旧归因刷新均不能覆盖当前账号`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "old"))
        val oldEpoch = sessions.current!!.epoch
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val repository = UserProfileRepository(object : UserProfileApi {
            override suspend fun profile(session: Session): ApiResponse<UserProfile> {
                calls++
                entered.complete(Unit); release.await()
                return ApiResponse(0, data = UserProfile(true))
            }
        }, sessions)
        val old = async { runCatching { repository.refresh() } }
        entered.await()
        sessions.saveLogin(AuthResponse("new", "refresh", "other"))
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is ServiceFailure.Superseded)
        assertNull(repository.state.value)
        assertTrue(runCatching { repository.refresh(oldEpoch) }.exceptionOrNull() is ServiceFailure.Superseded)
        assertEquals(1, calls)
    }

    @Test fun `补报后资料失败保留最后一次真实模式`() = runBlocking {
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        var fail = false
        val repository = UserProfileRepository(object : UserProfileApi {
            override suspend fun profile(session: Session): ApiResponse<UserProfile> {
                if (fail) throw java.io.IOException()
                return ApiResponse(0, data = UserProfile(true))
            }
        }, sessions)
        repository.refresh(); fail = true
        assertTrue(runCatching { repository.refresh() }.isFailure)
        assertEquals(AppMode.B, repository.state.value?.mode)
    }
}
