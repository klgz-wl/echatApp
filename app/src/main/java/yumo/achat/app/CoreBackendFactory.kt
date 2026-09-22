package yumo.achat.app

import android.content.Context
import yumo.achat.core.backend.AchatBackendConfiguration
import yumo.achat.core.backend.AchatRepository

internal fun createAchatRepository(context: Context): AchatRepository =
    AchatRepository(
        context = context,
        configuration = createAchatBackendConfiguration(
            baseUrl = BuildConfig.ACHAT_API_BASE_URL,
            packageName = BuildConfig.APPLICATION_ID,
            clientVersion = BuildConfig.ACHAT_CLIENT_VERSION,
        ),
    )

internal fun createAchatBackendConfiguration(
    baseUrl: String,
    packageName: String,
    clientVersion: String,
): AchatBackendConfiguration = AchatBackendConfiguration(
    baseUrl = baseUrl,
    packageName = packageName,
    clientVersion = clientVersion,
)
