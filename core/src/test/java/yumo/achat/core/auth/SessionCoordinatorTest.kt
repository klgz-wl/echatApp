package yumo.achat.core.auth

import yumo.achat.core.network.AuthResponse
import yumo.achat.core.network.ServiceFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class MemoryStorage : SessionStorage {
    var saved: Session? = null
    var failWrite = false
    override suspend fun read() = saved
    override suspend fun write(session: Session?) { if (failWrite) throw IOException(); saved = session }
    override suspend fun deviceId() = "test-device"
}

class SessionCoordinatorTest {
    @Test fun `并发的过期请求只刷新一次`() = runBlocking {
        val coordinator = SessionCoordinator(MemoryStorage())
        coordinator.saveLogin(AuthResponse("old-token", "refresh", "user"))
        val expected = coordinator.current!!
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..6).map { executor.submit<String> {
                release.await()
                coordinator.refresh(expected) { calls.incrementAndGet(); AuthResponse("new-token", null, "user") }!!.token
            } }
            release.countDown()
            futures.forEach { assertEquals("new-token", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
            assertEquals("refresh", coordinator.current!!.refreshToken)
        } finally { executor.shutdownNow() }
    }
    @Test fun `旧请求不能刷新或清除重新登录的同一个账号`() = runBlocking {
        val coordinator = SessionCoordinator(MemoryStorage())
        coordinator.saveLogin(AuthResponse("first", "refresh", "user"))
        val previous = coordinator.current!!
        coordinator.saveLogin(AuthResponse("second", "refresh", "user"))
        assertNull(coordinator.refresh(previous) { fail("不应访问刷新接口"); AuthResponse("bad") })
        coordinator.invalidate(previous)
        coordinator.clear(previous)
        assertEquals("second", coordinator.current!!.token)
    }
    @Test fun `存储失败不能发布登录成功状态`() = runBlocking {
        val storage = MemoryStorage().apply { failWrite = true }
        val coordinator = SessionCoordinator(storage)
        try { coordinator.saveLogin(AuthResponse("token", "refresh", "user")); fail() } catch (_: IOException) { }
        assertNull(coordinator.current)
    }
    @Test fun `不完整认证响应不能建立会话`() = runBlocking {
        val coordinator = SessionCoordinator(MemoryStorage())
        for (response in listOf(AuthResponse("", "refresh", "user"), AuthResponse("token", null, "user"), AuthResponse("token", "refresh", ""))) {
            try { coordinator.saveLogin(response); fail() } catch (_: ServiceFailure.InvalidResponse) { }
            assertNull(coordinator.current)
        }
    }
    @Test fun `刷新不能更换用户身份`() = runBlocking {
        val coordinator = SessionCoordinator(MemoryStorage())
        coordinator.saveLogin(AuthResponse("token", "refresh", "user"))
        try { coordinator.refresh(coordinator.current!!) { AuthResponse("other", "refresh", "other-user") }; fail() }
        catch (_: ServiceFailure.InvalidResponse) { }
        assertEquals("user", coordinator.current!!.userId)
    }

    @Test fun `旧版本的认证失败不能清除已刷新会话`() = runBlocking {
        val coordinator = SessionCoordinator(MemoryStorage())
        coordinator.saveLogin(AuthResponse("token", "refresh", "user"))
        val previous = coordinator.current!!
        coordinator.refresh(previous) { AuthResponse("token", "refresh", "user") }
        assertEquals(1L, coordinator.current!!.revision)
        coordinator.invalidate(previous)
        assertNotNull(coordinator.current)
        coordinator.refresh(previous) { fail("相同过期请求不应再次刷新"); AuthResponse("token") }
        assertEquals(1L, coordinator.current!!.revision)
    }
    @Test fun `冷启动恢复相同会话退出清理持久化凭据`() = runBlocking {
        val storage = MemoryStorage()
        SessionCoordinator(storage).saveLogin(AuthResponse("token", "refresh", "user"))
        val restored = SessionCoordinator(storage)
        restored.restore()
        assertEquals("user", restored.current!!.userId)
        restored.clear(restored.current!!)
        assertNull(storage.saved)
        assertNull(restored.current)
    }
}
