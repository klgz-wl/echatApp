package yumo.achat.app.ui.imagevideo

import android.content.Context
import yumo.achat.app.BuildConfig
import yumo.achat.app.attribution.AchatAttributionRuntime
import yumo.achat.app.analytics.AchatAnalyticsRuntime
import yumo.achat.app.analytics.AnalyticsConsent
import yumo.achat.core.backend.AchatBackendConfiguration
import yumo.achat.core.backend.AchatRepository
import yumo.achat.core.backend.NoOpBackendAttribution

internal fun achatBackendConfiguration(): AchatBackendConfiguration =
    AchatBackendConfiguration(
        apiBaseUrl = BuildConfig.ACHAT_API_BASE_URL,
        packageName = BuildConfig.APPLICATION_ID,
        clientVersion = BuildConfig.ACHAT_CLIENT_VERSION,
    )

internal fun createAchatRepository(context: Context): AchatRepository {
    val analytics = AchatAnalyticsRuntime.get(context)
    return AchatRepository(
        context = context,
        configuration = achatBackendConfiguration(),
        attribution = if (AnalyticsConsent.granted(context)) {
            AchatAttributionRuntime.get(context)
        } else {
            NoOpBackendAttribution(achatBackendConfiguration())
        },
        onLoginResult = { success, reason, userId ->
            if (userId != null) analytics.identify(userId)
            analytics.track(
                name = "silent_login",
                parameters = buildMap {
                    put("is_success", success)
                    put("device_model", android.os.Build.MODEL.orEmpty())
                    put("os_version", android.os.Build.VERSION.RELEASE.orEmpty())
                    reason?.let { put("fail_reason", it) }
                },
                userId = userId,
            )
        },
    )
}
