package com.zorv.core.payment

import com.zorv.core.auth.*
import com.zorv.core.network.AuthResponse
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PaymentEngineTest {
    class Store : PaymentStorage { var records = emptyList<PaymentRecord>(); override suspend fun read() = records; override suspend fun write(records: List<PaymentRecord>) { this.records = records } }
    class Api : PaymentRepository {
        var official = false; var initializeFailure: Exception? = null; var queryFailure = false
        var status = "pending"; var fulfillment = "pending"; var initialized = mutableListOf<String>()
        override suspend fun initialize(orderId: String, session: Session): InitializedPayment {
            initialized += orderId; initializeFailure?.let { throw it }
            return if (official) InitializedPayment(orderId, "official", "google_play", "sdk", PaymentSdkParams("server-sku"))
            else InitializedPayment(orderId, "third_party", "channel", "webview", paymentUrl="https://pay.example/checkout", expiresAt="2099-01-01T00:00:00Z")
        }
        override suspend fun status(orderId: String, session: Session): PaymentOrderStatus {
            if (queryFailure) throw java.io.IOException()
            return PaymentOrderStatus(orderId, status, fulfillment)
        }
        override suspend fun event(orderId: String, event: PaymentClientEvent, session: Session) { }
    }
    class Fixture {
        val store = Store(); val api = Api(); val sessions = SessionCoordinator(MemoryStorage())
        var now = 1_000_000L; var orders = 0; var createFailure = false
        val events = mutableListOf<String>()
        val tracker = com.zorv.core.analytics.RecordingTracker()
        val config = PaymentConfiguration("https://test.example/", "test", 10, 600, 3000, 100, 3, 5000)
        fun engine() = PaymentEngine(store, api, PaymentOrderFactory { _, _ -> orders++; if(createFailure) throw java.io.IOException(); "order-$orders" }, sessions,
            PaymentEventSink { _, type, _, _ -> events += type }, PaymentClock { now }, config, tracker)
        suspend fun login(id: String = "user") { sessions.saveLogin(AuthResponse("token", "refresh", id)) }
    }
    @Test fun `第三方已支付待发币不记成功成功去重且不猜测实付金额`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        engine.buy("coins", "generation"); val key = engine.state.value.record!!.key
        engine.opened(key); engine.opened(key)
        assertEquals(1, f.tracker.events.count { it.name == "initiate_pay" })
        f.api.status = "paid"; engine.poll(key)
        assertFalse(f.tracker.events.any { it.values["status"] == "success" })
        assertTrue(f.tracker.events.any { it.values["status"] == "pending" })
        f.api.fulfillment = "fulfilled"; engine.poll(key); engine.poll(key); engine.restore()
        val successes = f.tracker.events.filter { it.values["status"] == "success" }
        assertEquals(setOf("pay_result", "payment_custom"), successes.map { it.name }.toSet())
        assertEquals(2, successes.size)
        assertTrue(successes.all { it.values["entry_source"] == "generation" && !it.values.containsKey("af_revenue") && it.user == "user" })
    }
    @Test fun `服务端取消订单单独记录取消不记交易失败`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        engine.buy("coins", "home_balance"); val key = engine.state.value.record!!.key
        engine.opened(key); f.api.status = "cancelled"; engine.poll(key)
        assertTrue(f.tracker.events.any { it.values["status"] == "cancelled" })
        assertFalse(f.tracker.events.any { it.values["status"] == "failed" })
    }
    @Test fun `关闭与轮询超时埋点不是交易失败`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        engine.buy("coins", "home_balance"); val key = engine.state.value.record!!.key
        engine.opened(key); engine.close(key)
        assertTrue(f.tracker.events.any { it.values["status"] == "closed" })
        assertFalse(f.tracker.events.any { it.values["status"] == "failed" })
        val timeout = Fixture(); timeout.login(); val waiting = timeout.engine()
        waiting.buy("coins", "home_balance"); val timeoutKey = waiting.state.value.record!!.key
        waiting.opened(timeoutKey); timeout.now += 600_001; waiting.poll(timeoutKey)
        assertTrue(timeout.tracker.events.any { it.values["status"] == "unknown" && it.values["stage"] == "poll_timeout" })
        assertFalse(timeout.tracker.events.any { it.values["status"] == "failed" })
    }
    @Test fun `每次支付失败发送一次提示且恢复历史订单不重放`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        val notices = mutableListOf<String>()
        var collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.failureEvents.collect { notices += it } }
        try {
            for (status in listOf(401, 403, 503)) {
                f.api.initializeFailure = PaymentFailure.Http(status, null)
                engine.buy("coins", "main"); yield()
            }
            assertEquals(3, notices.size)
            assertTrue(notices.all { it == f.sessions.current!!.epoch })
            assertEquals(2, f.orders)
            engine.restore(); yield(); assertEquals(3, notices.size)
            collector.cancelAndJoin()
            collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.failureEvents.collect { notices += it } }
            yield(); assertEquals(3, notices.size)
            f.api.initializeFailure = null
            engine.buy("coins", "main"); yield(); assertEquals(3, notices.size)
        } finally { collector.cancelAndJoin() }
    }
    @Test fun `官方失败提示但主动取消和等待支付不提示失败`() = runBlocking {
        val f = Fixture(); f.login(); f.api.official = true; val engine = f.engine()
        val notices = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.failureEvents.collect { notices += it } }
        try {
            engine.buy("coins", "main"); val key = engine.state.value.record!!.key
            engine.claimOfficial(key); engine.officialResult(key, true); yield()
            assertTrue(notices.isEmpty())
            engine.buy("coins", "main"); engine.claimOfficial(key); engine.officialResult(key, false); yield()
            assertTrue(notices.isEmpty())
            engine.officialResult(key, true, failed = true); yield()
            assertEquals(1, notices.size)
            engine.restore(); yield(); assertEquals(1, notices.size)
        } finally { collector.cancelAndJoin() }
    }
    @Test fun `收银台加载和真实失败提示而查询未知不误报支付失败`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        val notices = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.failureEvents.collect { notices += it } }
        try {
            engine.buy("coins", "main"); val key = engine.state.value.record!!.key
            engine.opened(key); engine.pageEvent(key, "page_load_error"); yield()
            assertEquals(1, notices.size)
            f.api.queryFailure = true; engine.poll(key); engine.poll(key); yield()
            assertEquals(1, notices.size)
            f.api.queryFailure = false; f.api.status = "failed"
            engine.poll(key); yield(); engine.poll(key); yield()
            assertEquals(2, notices.size)
        } finally { collector.cancelAndJoin() }
    }
    @Test fun `旧账号请求失败不向新账号发送提示`() = runBlocking {
        val f = Fixture(); f.login()
        val api = object : PaymentRepository by f.api {
            override suspend fun initialize(orderId: String, session: Session): InitializedPayment {
                f.login("other")
                throw PaymentFailure.Http(403, "payment order forbidden")
            }
        }
        val engine = PaymentEngine(f.store, api, PaymentOrderFactory { _, _ -> "order" }, f.sessions,
            PaymentEventSink { _, _, _, _ -> }, PaymentClock { f.now }, f.config)
        val notices = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { engine.failureEvents.collect { notices += it } }
        try {
            engine.buy("coins", "main"); yield()
            assertTrue(notices.isEmpty())
            assertEquals("other", f.sessions.current!!.userId)
        } finally { collector.cancelAndJoin() }
    }
    @Test fun `先完成建单再初始化且只能按初始化结果进入支付`() = runBlocking {
        for (channel in listOf("official", "third_party", "failure")) {
            val f = Fixture(); f.login()
            val calls = mutableListOf<String>()
            val orderStarted = CompletableDeferred<Unit>(); val orderReady = CompletableDeferred<Unit>()
            val initStarted = CompletableDeferred<Unit>(); val initReady = CompletableDeferred<Unit>()
            val api = object : PaymentRepository by f.api {
                override suspend fun initialize(orderId: String, session: Session): InitializedPayment {
                    calls += "initialize:$orderId"; initStarted.complete(Unit); initReady.await()
                    if (channel == "failure") throw PaymentFailure.Http(503, "PAYMENT_CHANNEL_UNAVAILABLE")
                    f.api.official = channel == "official"
                    return f.api.initialize(orderId, session)
                }
            }
            val engine = PaymentEngine(f.store, api, PaymentOrderFactory { _, _ ->
                calls += "create"; orderStarted.complete(Unit); orderReady.await(); "server-order"
            }, f.sessions, PaymentEventSink { _, _, _, _ -> }, PaymentClock { f.now }, f.config)
            val buying = launch { engine.buy("coins", "main") }
            orderStarted.await()
            assertEquals(listOf("create"), calls)
            assertEquals(PaymentStage.CREATING, engine.state.value.record?.stage)
            assertFalse(engine.state.value.checkoutVisible)
            orderReady.complete(Unit); initStarted.await()
            assertEquals(listOf("create", "initialize:server-order"), calls)
            assertEquals(PaymentStage.INITIALIZING, engine.state.value.record?.stage)
            assertFalse(engine.state.value.checkoutVisible)
            initReady.complete(Unit); buying.join()
            val record = engine.state.value.record!!
            if (channel == "official") {
                assertEquals("server-sku", engine.claimOfficial(record.key)?.initialized?.sdkParams?.productId)
                assertFalse(engine.state.value.checkoutVisible)
            } else {
                assertNull(engine.claimOfficial(record.key))
                assertEquals(channel == "third_party", engine.state.value.checkoutVisible)
            }
            assertEquals(1, calls.count { it == "create" })
        }
    }
    @Test fun `初始化失败重试复用同一业务单且第三方不依赖Play`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(409,"PAYMENT_INITIALIZATION_IN_PROGRESS")
        engine.buy("coins", "generation"); f.api.initializeFailure = null
        engine.buy("coins", "generation")
        assertEquals(1,f.orders); assertEquals(listOf("order-1","order-1"),f.api.initialized)
        assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `403停止旧单仅下一次主动购买才创建新单`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(403, "payment order forbidden")
        engine.buy("coins", "main")
        val rejected = engine.state.value.record!!
        assertEquals(PaymentStage.ACCESS_DENIED, rejected.stage)
        assertEquals("payment_order_forbidden", rejected.error)
        assertEquals(1, f.orders)
        assertFalse(engine.state.value.checkoutVisible)
        assertNotNull(f.sessions.current)
        engine.retry(rejected.key)
        assertEquals(listOf("order-1"), f.api.initialized)
        f.api.initializeFailure = null
        engine.buy("coins", "main")
        assertEquals(2, f.orders)
        assertEquals(listOf("order-1", "order-2"), f.api.initialized)
        assertEquals(rejected, f.store.records.first { it.key == rejected.key })
        assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `升级保留被拒绝旧单并迁移恢复策略但不自动发起购买`() = runBlocking {
        val f = Fixture(); f.login()
        val old = PaymentRecord("legacy", f.sessions.current!!.userId, "coins", "main", orderId = "legacy-order",
            stage = PaymentStage.INITIALIZING, error = "payment_order_forbidden")
        f.store.records = listOf(old)
        var engine = f.engine(); engine.restore()
        assertEquals(0, f.orders)
        assertTrue(f.api.initialized.isEmpty())
        assertEquals(old.copy(stage = PaymentStage.ACCESS_DENIED), f.store.records.single())
        assertNull(engine.state.value.record)
        engine.buy("coins", "main")
        assertEquals(listOf("order-1"), f.api.initialized)
        assertEquals(2, f.store.records.size)
        engine = f.engine(); engine.restore(); engine.buy("coins", "main")
        assertEquals(1, f.orders)
        assertEquals(PaymentStage.ACCESS_DENIED, f.store.records.first { it.key == old.key }.stage)
    }
    @Test fun `归属迁移不改变鉴权未决渠道未决或已拿到渠道的订单`() = runBlocking {
        val f = Fixture(); f.login()
        val base = PaymentRecord("auth", f.sessions.current!!.userId, "coins", "main", orderId = "auth-order",
            stage = PaymentStage.INITIALIZING, error = "payment_auth_failed")
        val channel = base.copy(key = "channel", orderId = "channel-order", error = "payment_channel_unavailable")
        val initialized = base.copy(key = "initialized", orderId = "initialized-order", error = "payment_order_forbidden",
            initialized = InitializedPayment("initialized-order", "official", "google_play", "sdk", PaymentSdkParams("sku")))
        f.store.records = listOf(base, channel, initialized)
        val engine = f.engine(); engine.restore()
        assertEquals(listOf(PaymentStage.INITIALIZING, PaymentStage.INITIALIZING, PaymentStage.INITIALIZING), f.store.records.map { it.stage })
        assertEquals(0, f.orders)
        assertTrue(f.api.initialized.isEmpty())
    }
    @Test fun `渠道不可用提示具体原因并保留原单等待后台恢复`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(503, "PAYMENT_CHANNEL_UNAVAILABLE")
        engine.buy("coins", "main")
        assertEquals("payment_channel_unavailable", engine.state.value.record?.error)
        assertFalse(engine.state.value.checkoutVisible)
        f.api.initializeFailure = null
        engine.buy("coins", "main")
        assertEquals(1, f.orders)
        assertTrue(engine.state.value.checkoutVisible)
        assertNull(engine.state.value.record?.error)
    }
    @Test fun `建单结果未知不能因再次点击重复创建`() = runBlocking {
        val f=Fixture(); f.login(); f.createFailure=true; val engine=f.engine()
        engine.buy("coins","main"); engine.buy("coins","main")
        assertEquals(1,f.orders); assertEquals(PaymentStage.UNCERTAIN,engine.state.value.record?.stage)
    }
    @Test fun `支付鉴权失败提示独立错误且重启后复用原订单重试`() = runBlocking {
        val f = Fixture(); f.login(); var engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(401, "invalid authorization token")
        engine.buy("coins", "main")
        assertEquals("payment_auth_failed", engine.state.value.record?.error)
        assertFalse(engine.state.value.checkoutVisible)
        assertNotNull(f.sessions.current)
        engine = f.engine(); engine.restore()
        assertEquals("payment_auth_failed", engine.state.value.record?.error)
        f.api.initializeFailure = null
        engine.buy("coins", "main")
        assertEquals(1, f.orders)
        assertEquals(listOf("order-1", "order-1"), f.api.initialized)
        assertNull(engine.state.value.record?.error)
        assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `官方指令仅领取一次返回服务端SKU且恢复不自动再次付款`() = runBlocking {
        val f=Fixture(); f.login(); f.api.official=true; val engine=f.engine(); engine.buy("coins","main")
        val key=engine.state.value.record!!.key
        assertEquals("server-sku",engine.claimOfficial(key)?.initialized?.sdkParams?.productId)
        assertNull(engine.claimOfficial(key)); engine.officialResult(key,true)
        assertEquals(PaymentStage.CLOSED,engine.state.value.record?.stage)
        engine.buy("coins","main"); assertNotNull(engine.claimOfficial(key)); assertEquals(1,f.orders)
    }
    @Test fun `待发币不是成功关闭与轮询并发只认一次成功`() = runBlocking {
        val f=Fixture(); f.login(); val engine=f.engine(); engine.buy("coins","generation")
        val key=engine.state.value.record!!.key; engine.opened(key)
        f.api.status="paid"; engine.poll(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT,engine.state.value.record?.stage)
        f.api.fulfillment="fulfilled"
        coroutineScope { launch { engine.poll(key) }; launch { engine.close(key) } }
        assertEquals(PaymentStage.SUCCESS,engine.state.value.record?.stage)
        assertEquals(1,f.events.count { it.startsWith("paid_") })
        engine.shown(key); val shown=engine.state.value.record?.successShownAt
        f.now+=1000; engine.shown(key); assertEquals(shown,engine.state.value.record?.successShownAt)
    }
    @Test fun `超时断网关闭重启均保留订单且截止不重置`() = runBlocking {
        val f=Fixture(); f.login(); var engine=f.engine(); engine.buy("coins","main")
        val key=engine.state.value.record!!.key; engine.opened(key); val deadline=engine.state.value.record!!.deadline!!
        f.api.queryFailure=true; engine.close(key)
        assertEquals(PaymentStage.CLOSED,engine.state.value.record?.stage)
        engine=f.engine(); engine.restore(); f.now=deadline+1; engine.retry(key)
        assertEquals(PaymentStage.TIMED_OUT,engine.state.value.record?.stage)
        assertEquals(deadline,engine.state.value.record?.deadline); assertEquals(1,f.orders)
        f.api.queryFailure=false; f.api.status="paid";f.api.fulfillment="fulfilled";engine.poll(key)
        assertEquals(PaymentStage.SUCCESS,engine.state.value.record?.stage)
    }
    @Test fun `其他账号不能查询或确认旧订单`() = runBlocking {
        val f=Fixture();f.login();val engine=f.engine();engine.buy("coins","main");val key=engine.state.value.record!!.key
        f.login("other");engine.restore();engine.poll(key);engine.acknowledge(key)
        assertNull(engine.state.value.record);assertFalse(f.store.records.single().successAcknowledged)
    }
    @Test fun `无法关联的通知只查三方且不能跳过发币条件`() = runBlocking {
        val f=Fixture();f.login();val engine=f.engine();engine.buy("coins","main")
        assertTrue(engine.recharge(null));assertNotEquals(PaymentStage.SUCCESS,engine.state.value.record?.stage)
        f.api.status="paid";f.api.fulfillment="fulfilled";assertTrue(engine.recharge("order-1"))
        assertEquals(PaymentStage.SUCCESS,engine.state.value.record?.stage)
    }
    @Test fun `失败终态允许明确新购而本地时钟回拨不会延长窗口`() = runBlocking {
        val f=Fixture();f.login();val engine=f.engine();engine.buy("coins","main");val key=engine.state.value.record!!.key
        engine.opened(key);f.now--;engine.poll(key);assertEquals(PaymentStage.TIMED_OUT,engine.state.value.record?.stage)
        f.api.status="failed";engine.poll(key);engine.buy("coins","main");assertEquals(2,f.orders)
    }
    @Test fun `另一订单通知不会关闭当前收银台且重启不重放生成返回`() = runBlocking {
        val f=Fixture(); f.login(); val engine=f.engine()
        engine.buy("first","generation");val first=engine.state.value.record!!
        engine.buy("second","main");val second=engine.state.value.record!!
        f.api.status="paid";f.api.fulfillment="fulfilled";engine.recharge(first.orderId)
        assertEquals(second.key,engine.state.value.record?.key);assertTrue(engine.state.value.checkoutVisible)
        assertEquals(PaymentStage.SUCCESS,f.store.records.first { it.key==first.key }.stage)
        f.api.status="pending";engine.close(second.key)
        assertEquals(first.key,engine.state.value.record?.key);assertEquals("restored",engine.state.value.record?.source)
        val restored=f.engine();restored.restore();assertEquals("restored",restored.state.value.record?.source)
    }
    @Test fun `关闭后前台单次核对不会重新开始轮询窗口`() = runBlocking {
        val f=Fixture(); f.login();val engine=f.engine();engine.buy("coins","main")
        val key=engine.state.value.record!!.key;engine.opened(key);engine.close(key);engine.poll(key)
        assertEquals(PaymentStage.CLOSED,engine.state.value.record?.stage);assertFalse(engine.state.value.checkoutVisible)
        engine.retry(key);assertEquals(PaymentStage.CHECKOUT,engine.state.value.record?.stage);assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `保存失败禁止调用业务建单或初始化`() = runBlocking {
        val f=Fixture(); f.login()
        val broken=object:PaymentStorage {
            override suspend fun read()=emptyList<PaymentRecord>()
            override suspend fun write(records:List<PaymentRecord>){throw java.io.IOException()}
        }
        val engine=PaymentEngine(broken,f.api,PaymentOrderFactory { _,_ -> f.orders++;"order" },f.sessions,
            PaymentEventSink { _,_,_,_ -> },PaymentClock { f.now },f.config)
        engine.buy("coins","main");assertTrue(engine.state.value.storageFailed);assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
    }

    @Test fun `重启前尚未拉起的官方订单需用户再次点击而消费完成允许下一次购买`() = runBlocking {
        val f=Fixture();f.login();f.api.official=true;var engine=f.engine();engine.buy("coins","main")
        val key=engine.state.value.record!!.key;engine=f.engine();engine.restore();assertNull(engine.claimOfficial(key))
        engine.buy("coins","main");assertNotNull(engine.claimOfficial(key));engine.officialResult(key,false,true)
        assertEquals(PaymentStage.OFFICIAL_CONSUMED,engine.state.value.record?.stage)
        assertFalse(engine.state.value.record!!.successAcknowledged)
        engine.buy("coins","main");assertEquals(2,f.orders)
    }

}
