package yumo.achat.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FirebaseAnalyticsSinkTest {
    @Test
    fun `firebase sink prefixes events and synchronizes identity`() {
        val client = FakeFirebaseClient()
        val sink = FirebaseAnalyticsSink(client)

        sink.initialize()
        sink.identify("user-1")
        sink.event("page_view", mapOf("page_name" to "video", "position" to 2L))

        assertEquals(1, client.initializeCount)
        assertEquals("user-1", client.userId)
        assertEquals("f_page_view", client.eventName)
        assertEquals(2L, client.parameters["position"])
    }

    @Test
    fun `firebase sink applies firebase parameter limit locally and preserves payment keys`() {
        val client = FakeFirebaseClient()
        val sink = FirebaseAnalyticsSink(client)
        val parameters = (1..30).associate { "key$it" to it.toLong() } + mapOf(
            "user_id" to "user",
            "af_order_id" to "order",
            "package_id" to "coins",
            "af_content_id" to "sku",
            "af_price" to 4.99,
            "af_currency" to "USD",
            "af_revenue" to 4.99,
            "entry_source" to "store",
            "trigger" to "store",
            "self_user_id" to "user",
            "af_success" to "true",
            "failed_reason" to "none",
        )

        sink.event("purchase_success", parameters)

        assertEquals("f_purchase_success", client.eventName)
        assertEquals(25, client.parameters.size)
        for (key in listOf("user_id", "af_order_id", "package_id", "af_content_id", "af_price", "af_currency",
            "af_revenue", "entry_source", "trigger", "self_user_id", "af_success", "failed_reason")) {
            assertEquals(parameters[key], client.parameters[key])
        }
        assertEquals(1L, client.parameters["key1"])
        assertEquals(13L, client.parameters["key13"])
        assertEquals(null, client.parameters["key14"])
    }

    @Test
    fun `campaign attribution sets properties and emits unprefixed campaign details`() {
        val client = FakeFirebaseClient()
        val sink = FirebaseAnalyticsSink(client)

        sink.recordCampaignAttribution(buildJsonObject {
            put("af_status", "Non-organic")
            put("media_source", "network-a")
            put("campaign", "spring")
            put("af_channel", "social")
            put("af_adset", "set-1")
            put("af_ad", "creative-1")
        })

        assertEquals("Non-organic", client.properties["attribution_status"])
        assertEquals("network-a", client.properties["media_source"])
        assertEquals("campaign_details", client.eventName)
        assertEquals("spring", client.parameters["campaign"])
        assertEquals("social", client.parameters["medium"])
    }

    private class FakeFirebaseClient : FirebaseClient {
        var initializeCount = 0
        var userId: String? = null
        var eventName = ""
        var parameters: Map<String, Any> = emptyMap()
        val properties = mutableMapOf<String, String?>()
        override fun initialize() { initializeCount += 1 }
        override fun identify(userId: String?) { this.userId = userId }
        override fun event(name: String, parameters: Map<String, Any>) {
            eventName = name
            this.parameters = parameters
        }
        override fun setUserProperty(name: String, value: String?) { properties[name] = value }
    }
}
