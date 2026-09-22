package yumo.achat.core.auth

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import yumo.achat.core.config.CoreRuntimeConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import yumo.achat.core.attribution.AttributionStorage
import kotlinx.serialization.json.JsonObject

@Singleton
class PreferenceSessionStorage @Inject constructor(@ApplicationContext context: Context, config: CoreRuntimeConfig) : SessionStorage, AttributionStorage {
    private val data = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(config.storage.preferencesName) }
    private val sessionKey = stringPreferencesKey("auth_session")
    private val deviceKey = stringPreferencesKey("device_id")
    private val attributionKey = stringPreferencesKey("appsflyer_install_attribution")
    override suspend fun readAttribution(): JsonObject? = data.data.first()[attributionKey]?.let { Json.decodeFromString<JsonObject>(it) }
    override suspend fun writeAttribution(data: JsonObject) {
        this.data.edit { it[attributionKey] = data.toString() }
    }
    override suspend fun read(): Session? = data.data.first()[sessionKey]?.let { Json.decodeFromString<Session>(it) }
    override suspend fun write(session: Session?) {
        data.edit { if (session == null) it.remove(sessionKey) else it[sessionKey] = Json.encodeToString(Session.serializer(), session) }
    }
    override suspend fun deviceId(): String {
        var value = ""
        data.edit {
            value = it[deviceKey]?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString().also { id -> it[deviceKey] = id }
        }
        return value
    }
}
