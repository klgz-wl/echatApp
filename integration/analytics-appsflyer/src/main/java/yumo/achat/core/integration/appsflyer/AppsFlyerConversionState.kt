package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import yumo.achat.core.attribution.ConversionResult
import yumo.achat.core.attribution.hasValidAttribution
import yumo.achat.core.attribution.isMediaAttribution

internal class AppsFlyerConversionState {
    private val mutable = MutableStateFlow<ConversionResult>(ConversionResult.Pending)
    val conversion = mutable.asStateFlow()
    val data: JsonObject? get() = (mutable.value as? ConversionResult.Success)?.data

    fun sdkSnapshot(snapshot: JsonObject) {
        if (snapshot.hasValidAttribution()) {
            if (data?.isMediaAttribution() != true || snapshot.isMediaAttribution()) {
                mutable.value = ConversionResult.Success(snapshot)
            }
        } else sdkFailure()
    }

    fun sdkFailure() {
        if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Failed
    }

    fun httpMedia(snapshot: JsonObject) {
        if (snapshot.isMediaAttribution() && data?.isMediaAttribution() != true) {
            mutable.value = ConversionResult.Success(snapshot)
        }
    }

    fun startPending() {
        if (mutable.value !is ConversionResult.Success) mutable.value = ConversionResult.Pending
    }
}
