package com.vexora.core.integration.appsflyer

import org.junit.Assert.*
import org.junit.Test

class ForegroundAttributionStartTest {
    private class Host(var valid: Boolean = true)
    private class Fixture {
        val started = mutableListOf<Host>()
        var succeeds = true
        val start = ForegroundAttributionStart<Host>({ it.valid }) { started += it; succeeds }
    }
    @Test fun `没有获准前台宿主时首次请求和重试都不能启动`() {
        val f = Fixture(); f.start.request(); f.start.request(retry = true)
        assertTrue(f.started.isEmpty())
        val activity = Host(); f.start.attach(activity)
        assertEquals(listOf(activity), f.started)
    }
    @Test fun `宿主在首帧之后绑定也立即启动且传入原宿主`() {
        val f = Fixture(); val activity = Host()
        f.start.attach(activity); assertTrue(f.started.isEmpty())
        f.start.request(); assertSame(activity, f.started.single())
    }
    @Test fun `重复初始化和普通前台恢复不重复提交start`() {
        val f = Fixture(); val activity = Host()
        f.start.attach(activity); f.start.request(); f.start.request()
        f.start.detach(activity); f.start.attach(activity)
        assertEquals(1, f.started.size)
    }
    @Test fun `后台重试合并并在新Activity恢复后才提交`() {
        val f = Fixture(); val old = Host(); val next = Host()
        f.start.attach(old); f.start.request(); f.start.detach(old)
        f.start.request(retry = true); f.start.request(retry = true)
        assertEquals(listOf(old), f.started)
        f.start.attach(next)
        assertEquals(listOf(old, next), f.started)
    }
    @Test fun `旧Activity迟到暂停不能清除新Activity`() {
        val f = Fixture(); val old = Host(); val next = Host()
        f.start.attach(old); f.start.attach(next); f.start.detach(old); f.start.request()
        assertSame(next, f.started.single())
    }
    @Test fun `无效宿主不启动异常后的下一次请求可重试`() {
        val f = Fixture(); f.start.attach(Host(false)); f.start.request()
        assertTrue(f.started.isEmpty())
        val valid = Host(); f.succeeds = false; f.start.attach(valid)
        f.succeeds = true; f.start.request(); f.start.request()
        assertEquals(listOf(valid, valid), f.started)
    }
}
