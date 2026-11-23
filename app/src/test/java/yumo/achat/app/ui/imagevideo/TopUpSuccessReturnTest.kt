package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TopUpSuccessReturnTest {
    @Test fun failedWalletRefreshStillReturnsToEpisodeUnlock() = runBlocking {
        val episodeRoute=BottomNavigationRoute(0,ImageToVideoDestination.ShortDramaPlayer)
        var route=BottomNavigationRoute(2,ImageToVideoDestination.Templates)
        var pending: BottomNavigationRoute?=episodeRoute
        completeTopUpSuccess(refreshBalance={false},returnToSource={route=pending!!;pending=null})
        assertEquals(episodeRoute,route)
        assertNull(pending)
    }
    @Test fun slowWalletRefreshDoesNotHoldSuccessfulPurchaseOnTopUp() = runBlocking {
        val balance=CompletableDeferred<Boolean>()
        var returned=false
        val work=launch(start=CoroutineStart.UNDISPATCHED) {
            completeTopUpSuccess(refreshBalance={balance.await()},returnToSource={returned=true})
        }
        try { assertTrue(returned) } finally { balance.complete(true);work.join() }
    }
}
