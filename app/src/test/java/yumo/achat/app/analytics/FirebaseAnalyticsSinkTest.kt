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
