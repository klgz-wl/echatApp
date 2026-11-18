package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.ShortDramaEpisode

class DramaPreviewPolicyTest {
    @Test fun restrictedPreviewStopsOnMediaPositionNotElapsedWallTime() {
        val paid=ShortDramaEpisode("e",3,"",5,true,false,"VIP_REQUIRED")
        assertFalse(previewShouldStop(9999,paid))
        assertTrue(previewShouldStop(10000,paid))
        assertFalse(previewShouldStop(10000,null))
        assertFalse(previewShouldStop(10000,paid.copy(canPlay=true)))
    }
    @Test fun completedPreviewGatesUseCurrentEpisodeRights() {
        val e=ShortDramaEpisode("e",3,"",5,true,false,"VIP_REQUIRED")
        assertEquals(DramaPreviewGate.Subscribe,previewGate(true,e,false))
        assertEquals(DramaPreviewGate.Unlock,previewGate(true,e.copy(accessReason="EPISODE_LOCKED"),true))
        assertEquals(DramaPreviewGate.Watch,previewGate(true,e.copy(canPlay=true),true))
        assertEquals(DramaPreviewGate.Watch,previewGate(false,e,false))
        assertEquals(DramaPreviewGate.Unavailable,previewGate(true,e.copy(accessReason="PRICE_NOT_CONFIGURED",amount=null),true))
    }
}
