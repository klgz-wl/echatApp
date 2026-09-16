package yumo.achat.app.data.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AchatRepository(
    context: Context,
    private val client: AchatBackendClient = AchatBackendClient(),
) {
    private val sessionStore = AchatSessionStore(context.applicationContext)

    suspend fun loadHomeData(): AchatHomeData = withContext(Dispatchers.IO) {
        val session = sessionStore.readSession() ?: client
            .anonymousLogin(sessionStore.deviceId())
            .also(sessionStore::saveSession)

        val profile = runCatching { client.userProfile(session.token) }.getOrNull()
        val currency = runCatching { client.userCurrency(session.token) }.getOrNull()
        val videoTemplates = runCatching { client.templates(session.token, "video") }.getOrDefault(emptyList())
        val imageTemplates = runCatching { client.templates(session.token, "image") }.getOrDefault(emptyList())

        AchatHomeData(
            session = session,
            profile = profile,
            currency = currency,
            videoTemplates = videoTemplates,
            imageTemplates = imageTemplates,
        )
    }
}
