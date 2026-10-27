package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsFlyerRuntimePolicyTest {
    @Test fun `acquisition resume requires both SDK enablement and collection permission`() {
        assertTrue(appsFlyerCollectionAllowed(enabled = true, canCollect = true))
        assertFalse(appsFlyerCollectionAllowed(enabled = false, canCollect = true))
        assertFalse(appsFlyerCollectionAllowed(enabled = true, canCollect = false))
        assertFalse(appsFlyerCollectionAllowed(enabled = false, canCollect = false))
    }
}
