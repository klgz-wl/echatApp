package yumo.achat.core.backend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionStorage

/** Payment and the handwritten host share the authoritative installation session. */
@Singleton
class AchatCoreSessionStorage @Inject constructor(@ApplicationContext context: Context) : SessionStorage {
    private val store = AchatSessionStore(context)
    @Volatile private var lastObservedToken: String? = null
    override suspend fun read(): Session? = store.readCoreSession()?.also { lastObservedToken = it.token }
    override suspend fun write(session: Session?) {
        store.writeCoreSession(session, expectedToken = lastObservedToken, preserveHostOnClear = true)
        if (session != null) lastObservedToken = session.token
    }
    override suspend fun deviceId(): String = store.deviceId()
}
