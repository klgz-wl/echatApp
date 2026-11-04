package yumo.achat.core.attribution

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.config.ClientIdentity

class AttributionSpecificationTest {
    private val identity = ClientIdentity("test.package", "2.0.0", 15)

    @Test fun `report excludes login only top level identifiers`() {
        val raw = buildJsonObject {
            put("af_status", "Non-organic"); put("af_uid", "af-install"); put("af_channel", "paid")
        }
        val report = deviceAttributionReport(raw, identity, "device", "user")
        assertFalse(report.containsKey("af_uid"))
        assertFalse(report.containsKey("channel"))
        assertEquals(JsonPrimitive("af-install"), report["extra_data"]!!.jsonObject["af_uid"])
    }

    @Test fun `generic adset takes priority over generic adgroup`() {
        val raw = buildJsonObject { put("adset", "set"); put("adset_id", "set-id"); put("adgroup", "old") }
        val payload = attributionPayload(raw, identity, "device", "af")
        assertEquals("set", payload.adgroup)
        assertEquals("set-id", payload.adgroupId)
    }

    @Test fun `meta creative field precedes adgroup fallback`() {
        val raw = buildJsonObject {
            put("is_fb", true); put("creative", "creative"); put("creative_id", "creative-id")
            put("adgroup", "fallback"); put("adgroup_id", "fallback-id")
        }
        val payload = attributionPayload(raw, identity, "device", "af")
        assertEquals("creative", payload.creative)
        assertEquals("creative-id", payload.creativeId)
    }

    @Test fun `login and report recursively remove advertising identifiers but preserve evidence types`() {
        val raw = buildJsonObject {
            put("af_status", "Non-organic"); put("GA-ID", "secret")
            putJsonArray("nested") {
                add(buildJsonObject { put("Google_Advertising-ID", "secret"); put("count", 3); put("known", true) })
            }
        }
        val login = attributionPayload(raw, identity, "device", "af")
        assertFalse(login.extraData.containsKey("GA-ID"))
        val nested = login.extraData["nested"]!!.jsonArray.single().jsonObject
        assertFalse(nested.containsKey("Google_Advertising-ID"))
        assertEquals(JsonPrimitive(3), nested["count"])
        assertEquals(JsonPrimitive(true), nested["known"])
        assertEquals(login.extraData, deviceAttributionReport(raw, identity, "device", null)["extra_data"])
    }
}
