package yumo.achat.core.integration.appsflyer

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yumo.achat.core.attribution.hasValidAttribution

class AppsFlyerAttributionSnapshotTest {
    @Test fun `conversion snapshot preserves attribution and af uid`() {
        val snapshot = appsFlyerConversionSnapshot(
            data = mutableMapOf(
                "af_status" to "Non-organic",
                "media_source" to "network",
                "is_first_launch" to true,
            ),
            afUid = "af-123",
        )

        assertTrue(snapshot.hasValidAttribution())
        assertEquals("af-123", snapshot.getValue("af_uid").jsonPrimitive.content)
        assertEquals("network", snapshot.getValue("media_source").jsonPrimitive.content)
    }

    @Test fun `app open attribution snapshot enters attribution pipeline`() {
        val snapshot = appsFlyerAppOpenAttributionSnapshot(
            data = mutableMapOf(
                "media_source" to "deeplink_network",
                "campaign" to "one_link",
            ),
            afUid = "af-456",
        )

        assertTrue(snapshot.hasValidAttribution())
        assertEquals("app_open_attribution", snapshot.getValue("af_callback_type").jsonPrimitive.content)
        assertEquals("Non-organic", snapshot.getValue("af_status").jsonPrimitive.content)
        assertEquals("af-456", snapshot.getValue("af_uid").jsonPrimitive.content)
        assertEquals("deeplink_network", snapshot.getValue("media_source").jsonPrimitive.content)
    }

    @Test fun `app open attribution snapshot does not synthesize attribution from empty callback`() {
        val snapshot = appsFlyerAppOpenAttributionSnapshot(data = emptyMap(), afUid = "af-789")

        assertEquals("app_open_attribution", snapshot.getValue("af_callback_type").jsonPrimitive.content)
        assertEquals("af-789", snapshot.getValue("af_uid").jsonPrimitive.content)
        assertTrue(!snapshot.hasValidAttribution())
    }

    @Test fun `app open attribution snapshot requires a real channel signal`() {
        val snapshot = appsFlyerAppOpenAttributionSnapshot(
            data = mapOf("af_status" to "Non-organic"),
            afUid = "af-789",
        )

        assertTrue(!snapshot.hasValidAttribution())
    }
}
