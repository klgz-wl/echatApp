package yumo.achat.core.integration.appsflyer

import org.junit.Assert.*
import org.junit.Test

class ForegroundAttributionStartTest {
    private class Host(var valid: Boolean = true)
    private class Fixture {
        val started = mutableListOf<Pair<Host, Long>>()
        var now = 0L
        var succeeds = true
        val start = ForegroundAttributionStart<Host>(
            valid = { it.valid },
            start = { host, attempt -> started += host to attempt; succeeds },
            timeoutMillis = 15_000,
            nowMillis = { now },
        )
    }
    @Test fun `没有获准前台宿主时首次请求和重试都不能启动`() {
        val f = Fixture(); f.start.request(); f.start.request(retry = true)
        assertTrue(f.started.isEmpty())
        val activity = Host(); f.start.attach(activity)
        assertEquals(listOf(activity), f.started.map { it.first })
    }
    @Test fun `旧Activity迟到暂停不能清除新Activity`() {
        val f = Fixture(); val old = Host(); val next = Host()
        f.start.attach(old); f.start.attach(next); f.start.detach(old); f.start.request()
        assertSame(next, f.started.single().first)
    }
    @Test fun `缓存conversion先返回不释放仍在途的Launch`() {
        val f = Fixture(); f.start.attach(Host()); f.start.request()
        val attempt = f.started.single().second
        f.start.conversionFinished(attempt)
        f.start.request(retry = true)
        assertEquals(1, f.started.size)
        f.now = 1_000; f.start.launchAccepted(attempt)
        assertEquals(2, f.started.size)
    }
    @Test fun `Launch成功只解除Launch，conversion仍受保护`() {
        val f = Fixture(); f.start.attach(Host()); f.start.request()
        f.start.launchAccepted(f.started.single().second)
        f.start.request(retry = true)
        assertEquals(1, f.started.size)
        f.now = 1_000; f.start.conversionFinished(f.started.single().second)
        assertEquals(2, f.started.size)
    }
    @Test fun `旧Launch响应不释放新尝试`() {
        val f = Fixture(); f.start.attach(Host()); f.start.request()
        val old = f.started.single().second
        f.now = 15_001; f.start.request(retry = true)
        assertEquals(2, f.started.size)
        f.start.launchAccepted(old)
        f.start.conversionFinished(old)
        f.start.request(retry = true)
        assertEquals(2, f.started.size)
    }
    @Test fun `当前Launch失败同时解除两种等待且允许下一次请求`() {
        val f = Fixture(); f.start.attach(Host()); f.start.request()
        f.start.launchFailed(f.started.single().second)
        f.now = 1_000; f.start.request(retry = true)
        assertEquals(2, f.started.size)
    }
    @Test fun `在途保护到期允许本地重试`() {
        val f = Fixture(); f.start.attach(Host()); f.start.request()
        f.start.request(retry = true)
        assertEquals(1, f.started.size)
        f.now = 15_000; f.start.request(retry = true)
        assertEquals(2, f.started.size)
    }
    @Test fun `无效宿主不启动异常后的下一次请求可重试`() {
        val f = Fixture(); f.start.attach(Host(false)); f.start.request()
        assertTrue(f.started.isEmpty())
        val valid = Host(); f.succeeds = false; f.start.attach(valid)
        f.now = 1_000; f.succeeds = true; f.start.request(retry = true)
        assertEquals(listOf(valid, valid), f.started.map { it.first })
    }

    @Test fun `Organic callback clears a queued SDK restart`() {
        val f = Fixture()
        f.start.attach(Host())
        f.start.request()
        val first = f.started.single().second
        f.start.request(retry = true)
        f.start.dropPendingRetry()
        f.now = 1_000
        f.start.launchAccepted(first)
        f.start.conversionFinished(first)

        assertEquals(1, f.started.size)
    }

    @Test fun `revoked access removes foreground SDK host and pending retry`() {
        val f = Fixture()
        val host = Host()
        f.start.attach(host)
        f.start.request()
        val first = f.started.single().second
        f.start.request(retry = true)
        f.start.revokeAccess()
        f.now = 1_000
        f.start.launchAccepted(first)
        f.start.conversionFinished(first)
        f.start.request(retry = true)
        assertEquals(1, f.started.size)
        f.start.attach(host)
        f.start.request(retry = true)
        assertEquals(2, f.started.size)
    }
}
