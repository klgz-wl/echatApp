package yumo.achat.core.attribution

import org.junit.Assert.*
import org.junit.Test

class AttributionAcquisitionFenceTest {
    @Test fun `restored bypass rejects a suspended old candidate`() {
        val fence = AttributionAcquisitionFence()
        val candidate = fence.ticket()!!
        fence.suppress()
        assertFalse(fence.permits(candidate))
        assertNull(fence.ticket())
    }

    @Test fun `later submission cannot authorize a previous pending callback`() {
        val fence = AttributionAcquisitionFence()
        val old = fence.ticket()!!
        fence.pause()
        fence.beginSubmission()
        assertFalse(fence.permits(old))
        assertTrue(fence.permits(fence.ticket()!!))
    }
}
