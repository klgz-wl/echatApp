package yumo.achat.core.auth

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import yumo.achat.core.attribution.AttributionStorage
import yumo.achat.core.attribution.DataStoreAttributionLedgerStore
import yumo.achat.core.backend.AchatSessionStore
import yumo.achat.core.config.CoreRuntimeConfig
import kotlinx.serialization.json.JsonObject

@Singleton
class PreferenceSessionStorage @Inject constructor(@ApplicationContext context: Context, config: CoreRuntimeConfig) : SessionStorage, AttributionStorage {
    private val installation = InstallationPreferences(context, config.storage.preferencesName)
    private val session = AchatSessionStore(installation) {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf(String::isNotBlank) ?: error("Android ID unavailable")
    }
    private val attribution = DataStoreAttributionLedgerStore(installation)
    override suspend fun readAttribution(): JsonObject? = attribution.readAttribution()
    override suspend fun writeAttribution(data: JsonObject) = attribution.writeAttribution(data)
    override suspend fun read(): Session? = session.readCoreSession()
    override suspend fun write(session: Session?) = this.session.writeCoreSession(session)
    override suspend fun deviceId(): String = session.deviceId()
}
