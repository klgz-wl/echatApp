package yumo.achat.core.backend

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Test

class AchatSessionManagerSingletonTest {
    @Test
    fun repositoriesShareApplicationSessionManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = AchatBackendConfiguration(
            baseUrl = "https://test.appjoly.com",
            packageName = "yumo.achat.app",
            clientVersion = "2.0.0",
        )

        assertSame(
            AchatSessionManager.application(context, configuration),
            AchatSessionManager.application(context, configuration),
        )
    }
}
