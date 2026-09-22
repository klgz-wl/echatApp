package yumo.achat.core.payment

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PurchaseFlowTest {
    @Test fun `切换配置只运行选定策略且服务失败不回退旧支付`() = runBlocking {
        for (flow in PurchaseFlow.entries) {
            val dispatcher = PurchaseFlowDispatcher(PurchaseFlowConfiguration(flow))
            val calls = mutableListOf<String>()
            try {
                dispatcher.dispatch(service = { calls += "initialize"; throw java.io.IOException() },
                    legacy = { calls += "play" })
            } catch (_: java.io.IOException) { }
            assertEquals(if (flow == PurchaseFlow.SERVICE) listOf("initialize") else listOf("play"), calls)
            dispatcher.dispatch(service = { calls += "retry" }, legacy = { calls += "retry" })
            assertEquals("retry", calls.last())
        }
    }

    @Test fun `重复购买不排队且历史订单续接等待当前交易结束`() = runBlocking {
        val dispatcher = PurchaseFlowDispatcher(PurchaseFlowConfiguration(PurchaseFlow.LEGACY))
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val buying = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(service = { fail("不能初始化新流程") }, legacy = {
                calls += "legacy"; release.await(); calls += "legacy_done"
            })
        }
        dispatcher.dispatch(service = { fail("重复触发") }, legacy = { fail("重复触发") })
        val restoring = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.resumeOwnedOrder { calls += "service_existing_order" }
        }
        assertEquals(listOf("legacy"), calls)
        release.complete(Unit); buying.join(); restoring.join()
        assertEquals(listOf("legacy", "legacy_done", "service_existing_order"), calls)
    }

    @Test fun `取消策略会释放下一次购买准入`() = runBlocking {
        val dispatcher = PurchaseFlowDispatcher(PurchaseFlowConfiguration(PurchaseFlow.LEGACY))
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(service = { fail() }, legacy = { awaitCancellation() })
        }
        pending.cancelAndJoin()
        var ran = false
        dispatcher.dispatch(service = { fail() }, legacy = { ran = true })
        assertTrue(ran)
    }

    class Store : LegacyOrderStorage {
        var records = emptyList<LegacyPaymentOrder>()
        override suspend fun read() = records
        override suspend fun write(orders: List<LegacyPaymentOrder>) { records = orders }
    }
    @Test fun `旧单通知按账号识别且切换重启后持续去重`() = runBlocking {
        val store = Store()
        val registry = LegacyOrderRegistry(store)
        registry.register("old-order", "user")
        registry.register("old-order", "user")
        assertEquals(1, store.records.size)
        assertEquals(LegacyRechargeMatch.UNKNOWN, registry.recharge("old-order", "another-user"))
        assertEquals(LegacyRechargeMatch.UNKNOWN, registry.recharge("service-order", "user"))
        assertEquals(LegacyRechargeMatch.UNKNOWN, registry.recharge(null, "user"))
        assertEquals(LegacyRechargeMatch.FIRST, LegacyOrderRegistry(store).recharge("old-order", "user"))
        assertEquals(LegacyRechargeMatch.DUPLICATE, LegacyOrderRegistry(store).recharge("old-order", "user"))
    }

    @Test fun `新旧存储独立切换旧流程后新单仍需服务端确认发币`() = runBlocking {
        val fixture = PaymentEngineTest.Fixture(); fixture.login()
        var engine = fixture.engine()
        engine.buy("coins", "generation")
        val order = engine.state.value.record!!
        val legacyStore = Store(); val legacy = LegacyOrderRegistry(legacyStore)
        legacy.register("legacy-order", "user")
        assertEquals(LegacyRechargeMatch.FIRST, legacy.recharge("legacy-order", "user"))
        assertEquals(order.orderId, fixture.store.records.single().orderId)
        engine = fixture.engine(); engine.restore()
        assertTrue(engine.recharge(order.orderId))
        assertNotEquals(PaymentStage.SUCCESS, engine.state.value.record!!.stage)
        fixture.api.status = "paid"; fixture.api.fulfillment = "pending"
        engine.recharge(order.orderId)
        assertNotEquals(PaymentStage.SUCCESS, engine.state.value.record!!.stage)
        fixture.api.fulfillment = "fulfilled"; engine.recharge(order.orderId)
        assertEquals(PaymentStage.SUCCESS, engine.state.value.record!!.stage)
        assertEquals(LegacyRechargeMatch.DUPLICATE, legacy.recharge("legacy-order", "user"))
    }
}
