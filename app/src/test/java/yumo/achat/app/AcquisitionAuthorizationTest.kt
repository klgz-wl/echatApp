package yumo.achat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcquisitionAuthorizationTest {
    @Test fun `valid submit while paused attaches on later resume`() {
        val authorization = AcquisitionAuthorization()
        authorization.authorize(validConsent = true, accessAllowed = true)
        assertFalse(authorization.canAttach(resumed = false, accessAllowed = true))
        assertTrue(authorization.canAttach(resumed = true, accessAllowed = true))
    }

    @Test fun `legacy valid session restored while paused attaches under existing consent policy on resume`() {
        val authorization = AcquisitionAuthorization()
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = true))

        authorization.authorize(validConsent = true, accessAllowed = true)

        assertTrue(authorization.authorized)
        assertFalse(authorization.canAttach(resumed = false, accessAllowed = true))
        assertTrue(authorization.canAttach(resumed = true, accessAllowed = true))
        assertFalse(authorization.canAttach(resumed = false, accessAllowed = true))
        assertTrue(authorization.canAttach(resumed = true, accessAllowed = true))
    }

    @Test fun `no session callback leaves login choice unauthorized and region gate still applies`() {
        val authorization = AcquisitionAuthorization()
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = true))
        authorization.authorize(validConsent = false, accessAllowed = true)
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = true))
        authorization.authorize(validConsent = true, accessAllowed = false)
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = true))
    }

    @Test fun `sign out or revoked gate removes authorization`() {
        val authorization = AcquisitionAuthorization()
        authorization.authorize(validConsent = true, accessAllowed = true)
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = false))
        authorization.revoke()
        assertFalse(authorization.canAttach(resumed = true, accessAllowed = true))
    }
}
