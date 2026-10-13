package yumo.achat.core.payment

import yumo.achat.core.auth.*
import yumo.achat.core.network.AuthResponse
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.wallet.CoinProduct

class PaymentEngineTest {
    class Store : PaymentStorage { var records = emptyList<PaymentRecord>(); override suspend fun read() = records; override suspend fun write(records: List<PaymentRecord>) { this.records = records } }
    class Api : PaymentRepository {
        var official = false; var initializeFailure: Exception? = null; var queryFailure = false
        var checkoutExpiresAt = "2099-01-01T00:00:00Z"
        var openMode = "webview"
        var status = "pending"; var fulfillment = "pending"; var initialized = mutableListOf<String>()
        override suspend fun initialize(orderId: String, session: Session): InitializedPayment {
            initialized += orderId; initializeFailure?.let { throw it }
            return if (official) InitializedPayment(orderId, "official", "google_play", "sdk", PaymentSdkParams("server-sku"))
            else InitializedPayment(orderId, "third_party", "channel", openMode, paymentUrl="https://pay.example/checkout", expiresAt=checkoutExpiresAt)
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
        val tracker = yumo.achat.core.analytics.RecordingTracker()
        val config = PaymentConfiguration("https://test.example/", "test", 10, 600, 3000, 100, 3, 5000)
        fun engine() = PaymentEngine(store, api, PaymentOrderFactory { _, _ ->
            orders++; if(createFailure) throw java.io.IOException()
            PaymentBusinessOrder("order-$orders", "account-hash", "profile-hash")
        }, sessions,
            PaymentEventSink { _, type, _, _, _ -> events += type }, PaymentClock { now }, config, tracker)
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
        assertTrue(timeout.events.contains("timeout_cancel"))
        assertTrue(timeout.tracker.events.any { it.values["status"] == "unknown" && it.values["stage"] == "poll_timeout" })
        assertFalse(timeout.tracker.events.any { it.values["status"] == "failed" })
    }
    @Test fun `外部浏览器超时也记录超时取消`() = runBlocking {
        val f = Fixture(); f.login(); f.api.openMode = "external_browser"; val engine = f.engine()
        engine.buy("coins", "home_balance"); val key = engine.state.value.record!!.key
        engine.opened(key)

        f.now += 600_001
        engine.poll(key)

        assertTrue(f.events.contains("timeout_cancel"))
        assertEquals(PaymentStage.TIMED_OUT, engine.state.value.record?.stage)
    }
    @Test fun `关闭第三方支付时按关闭结果区分用户取消和关闭时成功`() = runBlocking {
        val cancelled = Fixture(); cancelled.login(); val cancelEngine = cancelled.engine()
        cancelEngine.buy("coins", "home_balance"); val cancelKey = cancelEngine.state.value.record!!.key
        cancelEngine.opened(cancelKey); cancelEngine.close(cancelKey)
        assertTrue(cancelled.events.contains("user_cancel"))
        assertFalse(cancelled.events.contains("paid_while_open"))

        val paid = Fixture(); paid.login(); val paidEngine = paid.engine()
        paidEngine.buy("coins", "home_balance"); val paidKey = paidEngine.state.value.record!!.key
        paidEngine.opened(paidKey)
        paid.api.status = "paid"; paid.api.fulfillment = "fulfilled"
        paidEngine.close(paidKey)
        assertTrue(paid.events.contains("paid_on_close"))
        assertFalse(paid.events.contains("paid_while_open"))
    }
    @Test fun `外部浏览器回前台关闭入口按关闭结果归类`() = runBlocking {
        val f = Fixture(); f.login(); f.api.openMode = "external_browser"; val engine = f.engine()
        engine.buy("coins", "home_balance"); val key = engine.state.value.record!!.key
        engine.opened(key)
        f.api.status = "paid"; f.api.fulfillment = "fulfilled"

        engine.close(key)

        assertTrue(f.events.contains("paid_on_close"))
        assertFalse(f.events.contains("paid_while_open"))
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)
    }
    @Test fun `三方页面加载事件保留页面URL和HTML摘要`() = runBlocking {
        val f = Fixture(); f.login()
        var captured: PaymentClientEventDetails? = null
        val engine = PaymentEngine(f.store, f.api, PaymentOrderFactory { _, _ ->
            f.orders++; PaymentBusinessOrder("order-${f.orders}")
        }, f.sessions, PaymentEventSink { _, type, _, _, details ->
            if (type == "page_loaded") captured = details
        }, PaymentClock { f.now }, f.config, f.tracker)

        engine.buy("coins", "main")
        val key = engine.state.value.record!!.key
        engine.opened(key)
        engine.pageEvent(
            key,
            "page_loaded",
            PaymentClientEventDetails(url = "https://checkout.example/pay", htmlExcerpt = "<html>ERR</html>"),
        )

        assertEquals("https://checkout.example/pay", captured?.url)
        assertEquals("<html>ERR</html>", captured?.htmlExcerpt)
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
            assertEquals(3, f.orders)
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
        val engine = PaymentEngine(f.store, api, PaymentOrderFactory { _, _ -> PaymentBusinessOrder("order") }, f.sessions,
            PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { f.now }, f.config)
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
                calls += "create"; orderStarted.complete(Unit); orderReady.await(); PaymentBusinessOrder("server-order")
            }, f.sessions, PaymentEventSink { _, _, _, _, _ -> }, PaymentClock { f.now }, f.config)
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
    @Test fun `初始化失败后再次购买创建新业务单且第三方不依赖Play`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(409,"PAYMENT_INITIALIZATION_IN_PROGRESS")
        engine.buy("coins", "generation"); f.api.initializeFailure = null
        engine.buy("coins", "generation")
        assertEquals(2,f.orders); assertEquals(listOf("order-1","order-2"),f.api.initialized)
        assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `带错误的未决旧单不拦截新商品购买`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.store.records = listOf(
            PaymentRecord(
                key = "old",
                userId = "user",
                productId = "coins_100",
                source = "main",
                orderId = "old-order",
                stage = PaymentStage.OFFICIAL_CONSUMED,
                error = "payment_channel_unavailable",
            ),
        )

        engine.buy("coins_200", "main")

        assertEquals(1, f.orders)
        assertEquals("coins_200", engine.state.value.record?.productId)
        assertEquals(listOf("order-1"), f.api.initialized)
    }
    @Test fun `初始化返回已过期收银台时不暴露支付页面`() = runBlocking {
        val f = Fixture(); f.login(); f.now = paymentTime("2026-09-24T12:00:00Z")!!
        f.api.checkoutExpiresAt = "2026-09-24T11:59:59Z"
        val engine = f.engine()

        engine.buy("coins", "main")

        assertEquals(1, f.orders)
        assertEquals(PaymentStage.TIMED_OUT, engine.state.value.record?.stage)
        assertEquals("expired_link", engine.state.value.record?.error)
        assertFalse(engine.state.value.checkoutVisible)
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
    @Test fun `恢复不激活初始化失败旧单但保留已拿到渠道的订单`() = runBlocking {
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
        assertEquals("initialized", engine.state.value.record?.key)
    }
    @Test fun `渠道不可用提示具体原因且下次购买创建新单`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(503, "PAYMENT_CHANNEL_UNAVAILABLE")
        engine.buy("coins", "main")
        assertEquals("payment_channel_unavailable", engine.state.value.record?.error)
        assertFalse(engine.state.value.checkoutVisible)
        f.api.initializeFailure = null
        engine.buy("coins", "main")
        assertEquals(2, f.orders)
        assertEquals(listOf("order-1", "order-2"), f.api.initialized)
        assertTrue(engine.state.value.checkoutVisible)
        assertNull(engine.state.value.record?.error)
    }
    @Test fun `渠道不可用且有明确Google SKU时复用原单走官方兜底`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(503, "PAYMENT_CHANNEL_UNAVAILABLE")

        engine.buy(
            "coins",
            "main",
            CoinProduct("coins", "diamond", "Coins", 1.99, googleProductId = "play.coins"),
        )

        val record = engine.state.value.record!!
        assertEquals(1, f.orders)
        assertEquals(PaymentStage.OFFICIAL_READY, record.stage)
        assertEquals("play.coins", record.initialized?.sdkParams?.productId)
        assertEquals("order-1", record.orderId)
    }
    @Test fun `建单前失败无订单号时允许下一次按新商品重新建单`() = runBlocking {
        val f=Fixture(); f.login(); f.createFailure=true; val engine=f.engine()
        engine.buy("coins","main")
        assertEquals(1,f.orders); assertEquals(PaymentStage.UNCERTAIN,engine.state.value.record?.stage)
        assertNull(engine.state.value.record?.orderId)

        f.createFailure=false
        engine.buy("other-coins","main")

        assertEquals(2,f.orders)
        assertEquals("other-coins", engine.state.value.record?.productId)
        assertEquals("order-2", engine.state.value.record?.orderId)
    }
    @Test fun `建单前失败无订单号重启后不恢复为活跃支付`() = runBlocking {
        val f=Fixture(); f.login(); f.createFailure=true; var engine=f.engine()
        engine.buy("coins","main")

        engine = f.engine()
        engine.restore()

        assertNull(engine.state.value.record)
    }
    @Test fun `已有订单终态失败后重启不恢复且下次按新商品新建单`() = runBlocking {
        val f=Fixture(); f.login()
        f.store.records = listOf(
            PaymentRecord("failed", f.sessions.current!!.userId, "coins", "main", orderId = "order-old", stage = PaymentStage.FAILED, error = "payment_failed"),
            PaymentRecord("closed", f.sessions.current!!.userId, "coins", "main", orderId = "order-closed", stage = PaymentStage.CLOSED),
            PaymentRecord("timeout", f.sessions.current!!.userId, "coins", "main", orderId = "order-timeout", stage = PaymentStage.TIMED_OUT, error = "expired_link"),
        )

        var engine = f.engine()
        engine.restore()
        assertNull(engine.state.value.record)

        engine.buy("other-coins", "main")

        assertEquals(1, f.orders)
        assertEquals("other-coins", engine.state.value.record?.productId)
        assertEquals("order-1", engine.state.value.record?.orderId)
    }
    @Test fun `支付鉴权失败提示独立错误且重启后新建订单`() = runBlocking {
        val f = Fixture(); f.login(); var engine = f.engine()
        f.api.initializeFailure = PaymentFailure.Http(401, "invalid authorization token")
        engine.buy("coins", "main")
        assertEquals("payment_auth_failed", engine.state.value.record?.error)
        assertFalse(engine.state.value.checkoutVisible)
        assertNotNull(f.sessions.current)
        engine = f.engine(); engine.restore()
        assertNull(engine.state.value.record)
        f.api.initializeFailure = null
        engine.buy("coins", "main")
        assertEquals(2, f.orders)
        assertEquals(listOf("order-1", "order-2"), f.api.initialized)
        assertNull(engine.state.value.record?.error)
        assertTrue(engine.state.value.checkoutVisible)
    }
    @Test fun `官方指令仅领取一次返回服务端SKU且恢复不自动再次付款`() = runBlocking {
        val f=Fixture(); f.login(); f.api.official=true; val engine=f.engine(); engine.buy("coins","main")
        val key=engine.state.value.record!!.key
        val claimed = engine.claimOfficial(key)
        assertEquals("server-sku", claimed?.initialized?.sdkParams?.productId)
        assertEquals("account-hash", claimed?.obfuscatedAccountId)
        assertEquals("profile-hash", claimed?.obfuscatedProfileId)
        assertNull(engine.claimOfficial(key)); engine.officialResult(key,true)
        assertEquals(PaymentStage.CLOSED,engine.state.value.record?.stage)
        engine.buy("coins","main")
        val nextKey = engine.state.value.record!!.key
        assertNotEquals(key, nextKey)
        assertNull(engine.claimOfficial(key))
        assertNotNull(engine.claimOfficial(nextKey))
        assertEquals(2,f.orders)
    }
    @Test fun `官方购买回调后由服务端状态推进到发币成功`() = runBlocking {
        val f = Fixture(); f.login(); f.api.official = true
        val engine = f.engine(); engine.buy("coins", "main")
        val key = engine.state.value.record!!.key
        assertNotNull(engine.claimOfficial(key))
        engine.officialResult(key, cancelledOrUnavailable = false, consumed = false)
        engine.officialQuote(key, 4.99, "USD")
        f.api.status = "paid"; f.api.fulfillment = "pending"

        engine.poll(key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)

        f.api.fulfillment = "fulfilled"
        engine.poll(key)
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)
        val success = f.tracker.events.first { it.name == "pay_result" && it.values["status"] == "success" }
        assertEquals(4.99, success.values["af_price"])
        assertEquals("google_play", success.values["price_source"])
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
    @Test fun `延迟确认旧成功不能清除已开始的新支付`() = runBlocking {
        val f = Fixture(); f.login(); val engine = f.engine()
        engine.buy("first", "main")
        val firstKey = engine.state.value.record!!.key
        f.api.status = "paid"; f.api.fulfillment = "fulfilled"
        engine.recharge("order-1")
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)

        f.api.status = "pending"; f.api.fulfillment = "pending"
        engine.buy("second", "main")
        val secondKey = engine.state.value.record!!.key
        assertNotEquals(firstKey, secondKey)

        engine.acknowledge(firstKey)

        assertEquals(secondKey, engine.state.value.record?.key)
        assertEquals(PaymentStage.CHECKOUT, engine.state.value.record?.stage)
    }
    @Test fun `关闭后重启不恢复旧订单且下次购买创建新单`() = runBlocking {
        val f=Fixture(); f.login(); var engine=f.engine(); engine.buy("coins","main")
        val key=engine.state.value.record!!.key; engine.opened(key)
        f.api.queryFailure=true; engine.close(key)
        assertEquals(PaymentStage.CLOSED,engine.state.value.record?.stage)
        engine=f.engine(); engine.restore()
        assertNull(engine.state.value.record)
        f.api.queryFailure=false
        engine.buy("other-coins","main")
        assertEquals(2,f.orders)
        assertEquals("other-coins",engine.state.value.record?.productId)
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
    @Test fun `官方充值通知仍需查单确认服务端已发币`() = runBlocking {
        val f = Fixture(); f.login(); f.api.official = true
        val engine = f.engine(); engine.buy("coins", "main")
        val key = engine.state.value.record!!.key
        assertNotNull(engine.claimOfficial(key))
        engine.officialResult(key, cancelledOrUnavailable = false, consumed = false)
        f.api.status = "paid"; f.api.fulfillment = "pending"

        assertTrue(engine.recharge("order-1"))
        assertEquals(PaymentStage.AWAITING_FULFILLMENT, engine.state.value.record?.stage)

        f.api.fulfillment = "fulfilled"
        assertTrue(engine.recharge("order-1"))
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record?.stage)
    }
    @Test fun `失败终态允许明确新购而本地时钟回拨不会延长窗口`() = runBlocking {
        val f=Fixture();f.login();val engine=f.engine();engine.buy("coins","main");val key=engine.state.value.record!!.key
        engine.opened(key);f.now--;engine.poll(key);assertEquals(PaymentStage.TIMED_OUT,engine.state.value.record?.stage)
        f.api.status="failed";engine.poll(key);engine.buy("coins","main");assertEquals(2,f.orders)
    }
    @Test fun `未决订单完成前不能创建另一商品订单`() = runBlocking {
        val f=Fixture(); f.login(); val engine=f.engine()
        engine.buy("first","generation");val first=engine.state.value.record!!
        engine.buy("second","main")
        assertEquals(first.key, engine.state.value.record?.key)
        assertEquals(1, f.orders)
        f.api.status="paid";f.api.fulfillment="fulfilled";engine.recharge(first.orderId)
        assertEquals(PaymentStage.SUCCESS,f.store.records.first { it.key==first.key }.stage)
        engine.buy("second", "main")
        assertEquals(2, f.orders)
        assertEquals("second", engine.state.value.record?.productId)
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
        val engine=PaymentEngine(broken,f.api,PaymentOrderFactory { _,_ -> f.orders++; PaymentBusinessOrder("order") },f.sessions,
            PaymentEventSink { _,_,_,_,_ -> },PaymentClock { f.now },f.config)
        engine.buy("coins","main");assertTrue(engine.state.value.storageFailed);assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
    }

    @Test fun `重启前尚未拉起的官方订单需用户再次点击而消费完成允许下一次购买`() = runBlocking {
        val f=Fixture();f.login();f.api.official=true;var engine=f.engine();engine.buy("coins","main")
        val key=engine.state.value.record!!.key;engine=f.engine();engine.restore();assertNull(engine.claimOfficial(key))
        engine.buy("coins","main")
        val nextKey = engine.state.value.record!!.key
        assertNotEquals(key, nextKey)
        assertNull(engine.claimOfficial(key))
        assertNotNull(engine.claimOfficial(nextKey));engine.officialResult(nextKey,false,true)
        assertEquals(PaymentStage.OFFICIAL_CONSUMED,engine.state.value.record?.stage)
        assertFalse(engine.state.value.record!!.successAcknowledged)
        engine.buy("coins","main");assertEquals(3,f.orders)
    }

}
