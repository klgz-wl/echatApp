package yumo.achat.core.backend

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Test

class AchatSessionManagerSingletonTest {
    @Test
    fun repositoriesShareApplicationSessionManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertSame(
            AchatSessionManager.application(context),
            AchatSessionManager.application(context),
        )
    }
}
