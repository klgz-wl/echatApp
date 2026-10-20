package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import yumo.achat.core.attribution.ConversionResult
import yumo.achat.core.attribution.AttributionResultSource
import yumo.achat.core.attribution.hasValidAttribution
import yumo.achat.core.attribution.isMediaAttribution

internal class AppsFlyerConversionState {
    private val mutable = MutableStateFlow<ConversionResult>(ConversionResult.Pending)
    val conversion = mutable.asStateFlow()
    private val mutableResults = MutableSharedFlow<yumo.achat.core.attribution.AttributionResult>(replay = 16, extraBufferCapacity = 16)
    val results = mutableResults.asSharedFlow()
    val data: JsonObject? get() = (mutable.value as? ConversionResult.Success)?.data

    fun sdkSnapshot(snapshot: JsonObject) {
        if (snapshot.hasValidAttribution()) {
            if (data?.isMediaAttribution() != true || snapshot.isMediaAttribution()) {
                val result = ConversionResult.Success(snapshot, AttributionResultSource.APPSFLYER_SDK)
                mutable.value = result
                mutableResults.tryEmit(yumo.achat.core.attribution.AttributionResult(result.source, result.data))
            }
        } else sdkFailure()
    }

    fun sdkFailure() {
        if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Failed
    }

    fun auxiliarySnapshot(source: AttributionResultSource, snapshot: JsonObject) {
        if (!snapshot.hasValidAttribution()) return
        val current = mutable.value as? ConversionResult.Success
        if (current?.source == AttributionResultSource.APPSFLYER_SDK && current.data.isMediaAttribution()) return
        if (data?.isMediaAttribution() == true && !snapshot.isMediaAttribution()) return
        val result = ConversionResult.Success(snapshot, source)
        mutable.value = result
        mutableResults.tryEmit(yumo.achat.core.attribution.AttributionResult(result.source, result.data))
    }

    fun startPending() {
        if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Pending
    }

    fun restoreSnapshot(result: yumo.achat.core.attribution.AttributionResult) {
        if (result.data.hasValidAttribution()) {
            mutable.value = ConversionResult.Success(result.data, result.source)
        }
    }
}
