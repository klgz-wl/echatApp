package com.zorv.core.attribution

import com.zorv.core.auth.MemoryStorage
import com.zorv.core.config.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttributionCoordinatorTest {
    private class Source : ConversionSource {
        override val conversion = MutableStateFlow<ConversionResult>(ConversionResult.Pending)
        var starts = 0
        var gaid: String? = "test-gaid"
        val retries = java.util.concurrent.atomic.AtomicInteger()
        var succeedAt = Int.MAX_VALUE
        override fun startAttribution() { starts++ }
        override fun retryAttribution() {
            if (retries.incrementAndGet() == succeedAt) conversion.value = ConversionResult.Success(buildJsonObject { put("af_status", "Non-organic") })
            else conversion.value = ConversionResult.Failed
        }
        override fun attributionUid() = "af-id"
        override fun advertisingId() = gaid
    }
    private class Storage(var data: JsonObject? = null) : AttributionStorage {
        override suspend fun readAttribution() = data
        override suspend fun writeAttribution(data: JsonObject) { this.data = data }
    }
    private fun data(status: String) = buildJsonObject { put("af_status", status); put("is_first_launch", true) }
    private fun coordinator(source: Source, storage: Storage = Storage(), timeout: Long = 100) = AttributionCoordinator(
        source, storage, MemoryStorage(), ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(timeout, 10, 5000))

    @Test fun `启动等待有效回调但不再选择展示模式`() = runBlocking {
        for (status in listOf("Organic", "Non-organic")) {
            val source = Source(); val repo = coordinator(source, timeout = 1000)
            val waiting = async { repo.awaitInitial() }
            delay(20); assertFalse(waiting.isCompleted)
            source.conversion.value = ConversionResult.Success(data(status))
            assertTrue(waiting.await())
        }
    }
    @Test fun `首次失败或超时放行登录但不伪造自然归因`() = runBlocking {
        for (result in listOf(ConversionResult.Pending, ConversionResult.Failed, ConversionResult.Success(buildJsonObject { put("af_status", "unknown") }))) {
            val source = Source().apply { conversion.value = result; gaid = null }
            val repo = coordinator(source, timeout = 20)
            assertEquals(false, repo.awaitInitial())
            val payload = repo.forLogin()
            assertFalse(payload.extraData.containsKey("af_status"))
            assertEquals("test-device", payload.deviceId)
        }
    }
    @Test fun `登录归因等待缓存读取完成避免丢失历史投放参数`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = Source().apply { conversion.value = ConversionResult.Failed }
        val cache = object : AttributionStorage {
            override suspend fun readAttribution(): JsonObject { entered.complete(Unit); release.await(); return data("Non-organic") }
            override suspend fun writeAttribution(data: JsonObject) = Unit
        }
        val repo = AttributionCoordinator(source, cache, MemoryStorage(), ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(100, 10, 5000))
        entered.await(); val login = async { repo.forLogin() }; yield(); assertFalse(login.isCompleted)
        release.complete(Unit)
        assertEquals("Non-organic", login.await().extraData["af_status"]!!.jsonPrimitive.content)
    }
    @Test fun `启动失败放行登录并保留历史真实归因数据`() = runBlocking {
        val source = Source().apply { conversion.value = ConversionResult.Failed }
        val repo = coordinator(source, Storage(data("Non-organic")))
        assertEquals(false, repo.awaitInitial())
        assertEquals("Non-organic", repo.forLogin().extraData["af_status"]!!.jsonPrimitive.content)
    }
    @Test fun `迟到回调更新快照供补报与资料刷新且不重复启动SDK`() = runBlocking {
        val source = Source(); val storage = Storage(); val repo = coordinator(source, storage, 20)
        assertEquals(false, repo.awaitInitial())
        source.conversion.value = ConversionResult.Success(data("Non-organic"))
        assertEquals("Non-organic", repo.forLogin().extraData["af_status"]!!.jsonPrimitive.content)
        assertEquals(false, repo.awaitInitial())
        assertEquals(false, coordinator(Source(), storage).awaitInitial())
        assertEquals(1, source.starts)
    }
    @Test fun `并发模式查询只等待同一结果并只启动一次`() = runBlocking {
        val source = Source(); val repo = coordinator(source, timeout = 1000)
        val pending = List(5) { async { repo.awaitInitial() } }
        source.conversion.value = ConversionResult.Success(data("Non-organic"))
        assertTrue(pending.awaitAll().all { it })
        assertEquals(1, source.starts)
    }
    @Test fun `全局归因最多重试十次成功后提前停止`() = runBlocking {
        for (successAt in listOf(3, Int.MAX_VALUE)) {
            val source = Source().apply { conversion.value = ConversionResult.Failed; succeedAt = successAt }
            val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(20, 10, 10))
            assertEquals(false, repo.awaitInitial())
            val expected = minOf(successAt, 10)
            withTimeout(2000) { while (source.retries.get() < expected) delay(5) }
            delay(80)
            assertEquals(expected, source.retries.get()); assertEquals(false, repo.awaitInitial())
            if (successAt == 3) {
                assertEquals("Non-organic", repo.snapshots.value?.get("af_status")?.jsonPrimitive?.content)
                assertEquals("Non-organic", repo.forLogin().extraData["af_status"]?.jsonPrimitive?.content)
            }
        }
    }
    @Test fun `缓存读写卡住不阻塞归因超时路由`() = runBlocking {
        val source = Source()
        val storage = object : AttributionStorage {
            override suspend fun readAttribution(): JsonObject? = awaitCancellation()
            override suspend fun writeAttribution(data: JsonObject): Unit = awaitCancellation()
        }
        val repo = AttributionCoordinator(source, storage, MemoryStorage(), ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(20, 10, 5000))
        assertEquals(false, withTimeout(500) { repo.awaitInitial() })
        source.conversion.value = ConversionResult.Success(data("Non-organic"))
        assertEquals("Non-organic", withTimeout(500) { repo.forLogin() }.extraData["af_status"]?.jsonPrimitive?.content)
    }
    @Test fun `独立补报采用真实设备身份且保留归因类型`() {
        val raw = buildJsonObject { put("af_status", "Non-organic"); put("campaign_id", 123); put("is_first_launch", true) }
        val payload = deviceAttributionReport(raw, ClientIdentity("test.package", "2.0", 30), "device", "user")
        assertEquals(JsonPrimitive("AppsFlyer"), payload["attribution_source"])
        assertEquals(JsonPrimitive("android"), payload["platform"])
        assertEquals(JsonPrimitive("device"), payload["device_id"])
        assertEquals(JsonPrimitive("user"), payload["user_id"])
        assertEquals(raw, payload["extra_data"])
        assertFalse(deviceAttributionReport(raw, ClientIdentity("test.package", "2.0", 30), "device", null).containsKey("user_id"))
    }
    @Test fun `回调字段映射保留数据类型并采用真实Android身份`() {
        val raw = conversionJson(mapOf("af_status" to "Non-organic", "media_source" to "Facebook Ads",
            "campaign" to "campaign-name", "campaign_id" to 123, "af_adset" to "group", "af_adset_id" to "group-id",
            "af_ad" to "creative", "af_ad_id" to "creative-id", "af_channel" to "Meta", "country_code" to "US",
            "is_first_launch" to true, "nested" to mapOf("a" to listOf(1, false, null)))) as JsonObject
        val payload = attributionPayload(raw, ClientIdentity("android.package", "2.0", 30), "gaid", "af")
        assertEquals("appsflyer", payload.attributionSource); assertEquals("android", payload.platform)
        assertEquals("android.package", payload.packageName); assertEquals("2.0", payload.appVersion)
        assertEquals("Facebook Ads", payload.network); assertEquals("campaign-name", payload.campaign)
        assertEquals("123", payload.campaignId); assertEquals("group", payload.adgroup); assertEquals("group-id", payload.adgroupId)
        assertEquals("creative", payload.creative); assertEquals("creative-id", payload.creativeId)
        assertEquals("Meta", payload.channel); assertEquals("US", payload.country)
        assertEquals(raw, payload.extraData); assertEquals(JsonPrimitive(true), raw["is_first_launch"])
        assertEquals(JsonPrimitive(123), raw["campaign_id"])
    }
}
