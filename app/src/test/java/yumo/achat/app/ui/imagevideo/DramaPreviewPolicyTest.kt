package yumo.achat.app.ui.imagevideo

import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.ShortDramaEpisode

class DramaPreviewPolicyTest {
    @Test fun pausedAndBufferingTimeCannotFinishWithoutMediaPositionAdvancing() {
        val progress=DramaPreviewProgress(restricted=true)
        repeat(1000) {assertFalse(progress.update(2500))}
        assertFalse(progress.update(9999))
        assertTrue(progress.update(10000))
        assertFalse(progress.update(10100))
        assertFalse(progress.ended())
    }
    @Test fun shortVideoFinishesNaturallyOnceAndFreePreviewHasNoTenSecondGate() {
        val short=DramaPreviewProgress(restricted=true)
        assertFalse(short.update(2000));assertTrue(short.ended());assertFalse(short.ended())
        val free=DramaPreviewProgress(restricted=false)
        assertFalse(free.update(20000));assertTrue(free.ended())
    }
    @Test fun restrictedPreviewStopsOnMediaPositionNotElapsedWallTime() {
        val paid=ShortDramaEpisode("e",3,"",5,true,false,"VIP_REQUIRED")
        assertFalse(previewShouldStop(9999,paid))
        assertTrue(previewShouldStop(10000,paid))
        assertFalse(previewShouldStop(10000,null))
        assertFalse(previewShouldStop(10000,paid.copy(canPlay=true)))
    }
    @Test fun vipMembershipGatePrecedesCoinGateAndOrdinaryPaidDoesNotInventVip() {
        val vip=ShortDramaEpisode("e",1,"",5,true,false,"EPISODE_LOCKED")
        assertEquals(DramaPreviewGate.Subscribe,previewGate(true,vip,false))
        assertEquals(DramaPreviewGate.Unlock,previewGate(true,vip,true))
        assertEquals(DramaPreviewGate.Unlock,previewGate(true,vip.copy(vip=false),false))
        assertEquals(DramaPreviewGate.Watch,previewGate(true,vip.copy(amount=0,canPlay=true),true))
        assertFalse(previewShouldStop(20000,vip.copy(vip=false,amount=0,canPlay=true)))
        assertEquals(DramaPreviewGate.Watch,previewGate(true,vip.copy(canPlay=true),true))
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
