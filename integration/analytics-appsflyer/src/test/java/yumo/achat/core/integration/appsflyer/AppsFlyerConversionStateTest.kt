package yumo.achat.core.integration.appsflyer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.attribution.ConversionResult

class AppsFlyerConversionStateTest {
    private val organic = Json.parseToJsonElement("""{"af_status":"Organic","is_first_launch":true}""") as JsonObject
    private val httpMedia = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"Facebook Ads"}""") as JsonObject
    private val sdkMedia = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"sdk-network"}""") as JsonObject

    @Test fun `HTTP media survives a delayed SDK Organic callback`() {
        val state = AppsFlyerConversionState()
        state.sdkSnapshot(organic)
        state.httpMedia(httpMedia)
        state.sdkSnapshot(organic)

        assertEquals(httpMedia, (state.conversion.value as ConversionResult.Success).data)
    }

    @Test fun `SDK media wins when it arrives before or after HTTP media`() {
        val first = AppsFlyerConversionState()
        first.sdkSnapshot(sdkMedia)
        first.httpMedia(httpMedia)
        first.sdkFailure()
        assertEquals(sdkMedia, (first.conversion.value as ConversionResult.Success).data)

        val later = AppsFlyerConversionState()
        later.httpMedia(httpMedia)
        later.sdkSnapshot(sdkMedia)
        assertEquals(sdkMedia, (later.conversion.value as ConversionResult.Success).data)
    }

    @Test fun `HTTP Organic and invalid SDK callback do not create or replace media`() {
        val state = AppsFlyerConversionState()
        state.httpMedia(organic)
        assertEquals(ConversionResult.Pending, state.conversion.value)
        state.sdkSnapshot(Json.parseToJsonElement("""{"af_status":"unknown"}""") as JsonObject)
        assertEquals(ConversionResult.Failed, state.conversion.value)
    }
}
