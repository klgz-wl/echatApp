package yumo.achat.app.ui.imagevideo

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.auth.Session
import yumo.achat.core.backend.AchatCoreSessionStorage
import yumo.achat.core.backend.AchatSessionStore
import yumo.achat.core.backend.AuthSession

class CoreSessionEpochTest {
    @Test fun paymentSessionWritePreservesHostLoginEpoch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val preferences = context.getSharedPreferences("achat_backend_session", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val hostStore = AchatSessionStore(context)
            hostStore.saveSession(AuthSession("user", "old-token", "refresh", "session", true, "host-epoch"))
            val coreStore = AchatCoreSessionStorage(context)
            coreStore.read()

            coreStore.write(Session("new-token", "refresh", "user", "core-epoch", revision = 1))

            assertEquals("host-epoch", hostStore.readSession()?.epoch)
            assertEquals("new-token", hostStore.readSession()?.token)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
