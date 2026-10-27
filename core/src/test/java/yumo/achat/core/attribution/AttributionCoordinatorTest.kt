package yumo.achat.core.attribution

import yumo.achat.core.auth.MemoryStorage
import yumo.achat.core.config.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttributionCoordinatorTest {
    @Test fun `login reads fresh GAID independently of cached SDK identifier`() = runBlocking {
        val source = Source().apply { gaid = "stale-sdk-value" }
        var reads = 0
        val provider = AdvertisingIdProvider {
            reads++
            if (reads == 1) "12345678-1234-4234-8234-123456789abc" else null
        }
        val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1", 30),
            AttributionConfiguration(20, 1, 1), provider)
        val first = repo.forLogin()
        val second = repo.forLogin()
        assertEquals("12345678-1234-4234-8234-123456789abc", first.gaid)
        assertEquals(first.gaid, first.deviceId)
        assertNull(second.gaid)
        assertEquals("test-device", second.deviceId)
        assertEquals(2, reads)
    }
    @Test fun `GAID lookup error does not block attribution login`() = runBlocking {
        val repo = AttributionCoordinator(Source(), Storage(), MemoryStorage(), ClientIdentity("test.package", "1", 30),
            AttributionConfiguration(20, 1, 1), AdvertisingIdProvider { error("play services unavailable") })
        val login = repo.forLogin()
        assertNull(login.gaid)
        assertEquals("test-device", login.deviceId)
    }
    @Test fun `Organic without auxiliary capability waits stage but stops SDK retry`() = runBlocking {
        val source = Source()
        val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1.0", 30),
            AttributionConfiguration(80, 10, 10, loginTimeoutMillis = 80, homeTimeoutMillis = 80))
        val waiting = async { repo.awaitInitial() }
        delay(10)
        source.conversion.value = ConversionResult.Success(data("Organic"))
        delay(15)
        assertFalse(waiting.isCompleted)
        assertFalse(waiting.await())
        assertNotNull(repo.snapshots.value)
        val retriesAfterOrganic = source.retries.get()
        delay(30)
        assertEquals(retriesAfterOrganic, source.retries.get())
    }

    @Test fun `Organic arriving during wait finishes when auxiliary confirmation completes`() = runBlocking {
        val source = Source().apply { supportsConfirmation = true }
        val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1.0", 30),
            AttributionConfiguration(500, 10, 10, loginTimeoutMillis = 500, homeTimeoutMillis = 500))
        val waiting = async { repo.awaitInitial() }
        delay(20)
        source.conversion.value = ConversionResult.Success(data("Organic"))
        delay(20)
        assertFalse(waiting.isCompleted)
        source.confirmation.value = true
        assertFalse(waiting.await())
    }

    @Test fun `post auth window starts after authentication and media ends home early`() = runBlocking {
        val source = Source()
        val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1.0", 30),
            AttributionConfiguration(20, 10, 10, loginTimeoutMillis = 120, homeTimeoutMillis = 120))
        assertFalse(repo.awaitInitial())
        delay(40)
        val waiting = async { repo.awaitAfterAuth() }
        delay(30)
        assertFalse(waiting.isCompleted)
        source.conversion.value = ConversionResult.Success(data("Non-organic"))
        assertTrue(waiting.await())
        assertTrue(repo.awaitHome())
    }
    private class Source : ConversionSource {
        override val conversion = MutableStateFlow<ConversionResult>(ConversionResult.Pending)
        var starts = 0
        var gaid: String? = "test-gaid"
        val retries = java.util.concurrent.atomic.AtomicInteger()
        var succeedAt = Int.MAX_VALUE
        var supportsConfirmation = false
        val confirmation = MutableStateFlow(false)
        override val preHomeConfirmation get() = if (supportsConfirmation) confirmation else null
        override fun startAttribution() { starts++ }
        override fun retryAttribution() {
            if ((conversion.value as? ConversionResult.Success)?.data?.isMediaAttribution() == true) return
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
            val source = Source(); val repo = coordinator(source, timeout = 70)
            val waiting = async { repo.awaitInitial() }
            delay(20); assertFalse(waiting.isCompleted)
            source.conversion.value = ConversionResult.Success(data(status))
            assertEquals(status == "Non-organic", waiting.await())
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
    @Test fun `有效缓存只供登录使用不触发重复补报`() = runBlocking {
        val source = Source().apply { conversion.value = ConversionResult.Failed }
        val repo = coordinator(source, Storage(data("Non-organic")))

        repo.forLogin()

        assertEquals(null, repo.snapshots.value)
    }
    @Test fun `迟到回调更新快照供补报与资料刷新且不重复启动SDK`() = runBlocking {
        val source = Source(); val storage = Storage(); val repo = coordinator(source, storage, 20)
        assertEquals(false, repo.awaitInitial())
        source.conversion.value = ConversionResult.Success(data("Non-organic"))
        assertEquals("Non-organic", repo.forLogin().extraData["af_status"]!!.jsonPrimitive.content)
        assertEquals(true, repo.awaitInitial())
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
    @Test fun `全局重试超过十次后媒体成功才停止`() = runBlocking {
        val source = Source().apply { conversion.value = ConversionResult.Failed; succeedAt = 12 }
        val repo = AttributionCoordinator(source, Storage(), MemoryStorage(), ClientIdentity("test.package", "1.0", 30), AttributionConfiguration(20, 10, 10))
        assertEquals(false, repo.awaitInitial())
        withTimeout(2000) { while (source.retries.get() < 12) delay(5) }
        delay(80)
        assertEquals(12, source.retries.get())
        assertTrue(repo.awaitInitial())
        assertEquals("Non-organic", repo.snapshots.value?.get("af_status")?.jsonPrimitive?.content)
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
    @Test fun `独立补报采用真实设备身份且补齐后台归因字段`() {
        val raw = buildJsonObject {
            put("af_status", "Non-organic")
            put("campaign_id", 123)
            put("is_first_launch", true)
            put("af_uid", "af-uid")
            put("af_channel", "paid-social")
        }
        val payload = deviceAttributionReport(raw, ClientIdentity("test.package", "2.0", 30), "device", "user",
            gaid = "12345678-1234-4234-8234-123456789abc")
        assertEquals(JsonPrimitive("AppsFlyer"), payload["attribution_source"])
        assertEquals(JsonPrimitive("android"), payload["platform"])
        assertEquals(JsonPrimitive("device"), payload["device_id"])
        assertEquals(JsonPrimitive("12345678-1234-4234-8234-123456789abc"), payload["gaid"])
        assertEquals(JsonPrimitive("user"), payload["user_id"])
        assertEquals(JsonPrimitive("af-uid"), payload["af_uid"])
        assertEquals(JsonPrimitive("paid-social"), payload["channel"])
        val extra = payload["extra_data"] as JsonObject
        assertEquals(JsonPrimitive("af-uid"), extra["af_uid"])
        assertEquals(JsonPrimitive("user"), extra["app_user_id"])
        assertEquals(JsonPrimitive(true), extra["is_first_launch"])
        assertEquals(JsonPrimitive(123), extra["campaign_id"])
        assertFalse(deviceAttributionReport(raw, ClientIdentity("test.package", "2.0", 30), "device", null).containsKey("user_id"))
    }

    @Test fun `独立补报携带稳定结果元数据并区分来源`() {
        val raw = buildJsonObject { put("af_status", "Organic"); put("af_uid", "af-uid") }
        val payload = deviceAttributionReport(
            data = raw,
            identity = ClientIdentity("test.package", "2.0", 30),
            deviceId = "device",
            userId = "user",
            metadata = AttributionReportMetadata(
                reportId = "report-1",
                startupElapsedMillis = 321,
                resultIndex = 2,
                stage = AttributionStage.HOME,
                source = AttributionResultSource.GCD_HTTP,
            ),
        )

        assertEquals(JsonPrimitive("report-1"), payload["report_id"])
        assertEquals(JsonPrimitive(321), payload["startup_elapsed_ms"])
        assertEquals(JsonPrimitive(2), payload["attribution_result_index"])
        assertEquals(JsonPrimitive("home"), payload["attribution_stage"])
        assertEquals(JsonPrimitive("gcd_http"), payload["attribution_result_source"])
        assertEquals(JsonPrimitive(true), payload["is_gcd_http_result"])
        assertEquals(JsonPrimitive(false), payload["is_install_referrer_supplement"])
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

    @Test fun `Meta GCD adset and adgroup map to separate business levels`() {
        val raw = Json.parseToJsonElement("""{
            "af_status":"Non-organic","is_fb":true,"media_source":"Facebook Ads",
            "adset":"set-name","adset_id":"set-id","adgroup":"ad-name","adgroup_id":"ad-id",
            "nested":{"enabled":true,"counts":[1,null]}
        }""").jsonObject
        val identity = ClientIdentity("android.package", "2.0", 30)
        val login = attributionPayload(raw, identity, "device", "af-uid")
        val report = deviceAttributionReport(raw, identity, "device", "user")

        assertEquals("set-name", login.adgroup)
        assertEquals("set-id", login.adgroupId)
        assertEquals("ad-name", login.creative)
        assertEquals("ad-id", login.creativeId)
        assertEquals(JsonPrimitive("set-name"), report["adgroup"])
        assertEquals(JsonPrimitive("ad-name"), report["creative"])
        assertEquals(raw.getValue("nested"), login.extraData.getValue("nested"))
    }

    @Test fun `Meta mapping prefers af fields without promoting an ad to a missing adset`() {
        val identity = ClientIdentity("android.package", "2.0", 30)
        val preferred = Json.parseToJsonElement("""{
            "af_status":"Non-organic","is_fb":true,
            "af_adset":"sdk-set","af_adset_id":"sdk-set-id","adset":"gcd-set","adset_id":"gcd-set-id",
            "af_ad":"sdk-ad","af_ad_id":"sdk-ad-id","adgroup":"gcd-ad","adgroup_id":"gcd-ad-id"
        }""").jsonObject
        val adOnly = Json.parseToJsonElement("""{
            "af_status":"Non-organic","is_fb":true,"adgroup":"ad-name","adgroup_id":"ad-id"
        }""").jsonObject
        val generic = Json.parseToJsonElement("""{
            "af_status":"Non-organic","media_source":"other","adgroup":"generic-set","adgroup_id":"generic-set-id",
            "creative":"generic-ad","creative_id":"generic-ad-id"
        }""").jsonObject

        assertEquals("sdk-set", attributionPayload(preferred, identity, "device", "af").adgroup)
        assertEquals("sdk-ad", attributionPayload(preferred, identity, "device", "af").creative)
        assertEquals(null, attributionPayload(adOnly, identity, "device", "af").adgroup)
        assertEquals(null, attributionPayload(adOnly, identity, "device", "af").adgroupId)
        assertEquals("ad-name", attributionPayload(adOnly, identity, "device", "af").creative)
        assertEquals("generic-set", attributionPayload(generic, identity, "device", "af").adgroup)
        assertEquals("generic-ad", attributionPayload(generic, identity, "device", "af").creative)
    }
}
