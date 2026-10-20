package yumo.achat.core.integration.appsflyer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import yumo.achat.core.attribution.ConversionResult

class AppsFlyerConversionStateTest {
    @Test fun `source result is replayed to a collector that subscribes after callback`() = runBlocking {
        val state = AppsFlyerConversionState()
        state.sdkSnapshot(organic)
        val result = state.results.first()
        assertEquals(yumo.achat.core.attribution.AttributionResultSource.APPSFLYER_SDK, result.source)
        assertEquals(organic, result.data)
    }

    @Test fun `restored auxiliary result keeps its source without emitting a new result`() = runBlocking {
        val state = AppsFlyerConversionState()
        state.restoreSnapshot(yumo.achat.core.attribution.AttributionResult(
            yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP,
            httpMedia,
        ))
        val restored = state.conversion.value as ConversionResult.Success
        assertEquals(yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP, restored.source)
        assertEquals(httpMedia, restored.data)
        assertEquals(emptyList<Any>(), state.results.replayCache)
    }
    private val organic = Json.parseToJsonElement("""{"af_status":"Organic","is_first_launch":true}""") as JsonObject
    private val httpMedia = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"Facebook Ads"}""") as JsonObject
    private val sdkMedia = Json.parseToJsonElement("""{"af_status":"Non-organic","media_source":"sdk-network"}""") as JsonObject

    @Test fun `HTTP media survives a delayed SDK Organic callback`() {
        val state = AppsFlyerConversionState()
        state.sdkSnapshot(organic)
        state.auxiliarySnapshot(yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP, httpMedia)
        state.sdkSnapshot(organic)

        assertEquals(httpMedia, (state.conversion.value as ConversionResult.Success).data)
    }

    @Test fun `SDK media wins when it arrives before or after HTTP media`() {
        val first = AppsFlyerConversionState()
        first.sdkSnapshot(sdkMedia)
        first.auxiliarySnapshot(yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP, httpMedia)
        first.sdkFailure()
        assertEquals(sdkMedia, (first.conversion.value as ConversionResult.Success).data)

        val later = AppsFlyerConversionState()
        later.auxiliarySnapshot(yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP, httpMedia)
        later.sdkSnapshot(sdkMedia)
        assertEquals(sdkMedia, (later.conversion.value as ConversionResult.Success).data)
    }

    @Test fun `HTTP Organic is a valid independent result and invalid SDK callback does not replace it`() {
        val state = AppsFlyerConversionState()
        state.auxiliarySnapshot(yumo.achat.core.attribution.AttributionResultSource.GCD_HTTP, organic)
        assertEquals(organic, (state.conversion.value as ConversionResult.Success).data)
        state.sdkSnapshot(Json.parseToJsonElement("""{"af_status":"unknown"}""") as JsonObject)
        assertEquals(organic, (state.conversion.value as ConversionResult.Success).data)
    }
}
