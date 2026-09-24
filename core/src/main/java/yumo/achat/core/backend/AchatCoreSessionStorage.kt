package yumo.achat.core.backend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import yumo.achat.core.auth.Session
import yumo.achat.core.auth.SessionStorage

/** Core adapter over the installed identity/session store used by the running host. */
@Singleton
class AchatCoreSessionStorage @Inject constructor(
    @ApplicationContext context: Context,
) : SessionStorage {
    private val store = AchatSessionStore(context)
    private val metadata = context.getSharedPreferences("achat_backend_session", Context.MODE_PRIVATE)
    @Volatile private var lastObservedToken: String? = null

    override suspend fun read(): Session? = store.readSession()?.let { saved ->
        lastObservedToken = saved.token
        val previousUser = metadata.getString(KEY_USER, null)
        val epoch = metadata.getString(KEY_EPOCH, null).orEmpty().takeIf { previousUser == saved.userId }.orEmpty().ifBlank {
            UUID.randomUUID().toString().also { metadata.edit().putString(KEY_EPOCH, it).commit() }
        }
        metadata.edit().putString(KEY_USER, saved.userId).commit()
        Session(
            token = saved.token,
            refreshToken = saved.refreshToken,
            userId = saved.userId,
            epoch = epoch,
            revision = metadata.getLong(KEY_REVISION, 0L),
        )
    }

    override suspend fun write(session: Session?) {
        if (session == null) {
            // Core payment invalidation must not delete the host's authoritative login.
            metadata.edit().remove(KEY_EPOCH).remove(KEY_REVISION).remove(KEY_USER).commit()
            return
        }
        val previous = store.readSession()
        check(previous == null || previous.userId == session.userId) {
            "Host session owner changed"
        }
        val observed = lastObservedToken
        check(previous == null || observed == null || previous.token == observed || previous.token == session.token) {
            "Host session changed while Core payment refreshed"
        }
        val updated = AuthSession(
                userId = session.userId,
                token = session.token,
                refreshToken = session.refreshToken,
                sessionId = previous?.sessionId.orEmpty(),
                isAnonymous = previous?.isAnonymous ?: true,
            )
        check(store.saveSessionIfTokenUnchanged(previous?.token, updated)) {
            "Host session changed while Core payment refreshed"
        }
        metadata.edit()
            .putString(KEY_EPOCH, session.epoch)
            .putString(KEY_USER, session.userId)
            .putLong(KEY_REVISION, session.revision)
            .commit()
        lastObservedToken = session.token
    }

    override suspend fun deviceId(): String = store.deviceId()

    private companion object {
        const val KEY_EPOCH = "core_payment_epoch"
        const val KEY_REVISION = "core_payment_revision"
        const val KEY_USER = "core_payment_user"
    }
}
