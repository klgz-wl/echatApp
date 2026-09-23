package yumo.achat.app.ui.imagevideo

import android.content.Context
import yumo.achat.app.BuildConfig
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.core.backend.AchatBackendConfiguration
import yumo.achat.core.backend.AchatRepository

internal fun achatBackendConfiguration(): AchatBackendConfiguration =
    AchatBackendConfiguration(
        apiBaseUrl = BuildConfig.ACHAT_API_BASE_URL,
        packageName = BuildConfig.APPLICATION_ID,
        clientVersion = BuildConfig.ACHAT_CLIENT_VERSION,
    )

internal fun createAchatRepository(context: Context): AchatRepository =
    AchatRepository(
        context = context,
        configuration = achatBackendConfiguration(),
        attribution = AchatAttributionRuntime.get(context),
    )
