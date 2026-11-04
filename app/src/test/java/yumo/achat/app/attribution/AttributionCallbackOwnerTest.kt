package yumo.achat.app.attribution

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.auth.InstallationOwner

class AttributionCallbackOwnerTest {
    @Test fun `arrival time precedes identity capture and a later account cannot rewrite the event`() {
        var clock = 100L
        var published = InstallationOwner("device", "A")
        val event = captureAttributionCallbackOwner({ clock }) {
            clock = 200L
            published
        }
        published = InstallationOwner("device", "B")
        assertEquals(100L, event.eventTime)
        assertEquals("A", event.userId)
        assertEquals("B", published.userId)
    }
}
