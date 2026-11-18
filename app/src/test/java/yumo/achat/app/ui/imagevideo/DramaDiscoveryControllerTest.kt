package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.DramaPage

class DramaDiscoveryControllerTest {
    @Test fun paginationFailureRetainsItemsAndRetriesSamePage() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<Int>(); var fail=true
        val c=DramaPageController(scope,{ it:String -> it }) { page ->
            calls+=page
            if(page==2 && fail) error("offline")
            DramaPage(listOf(if(page==1) "a" else "b"),2,page,1)
        }
        c.reload(); c.more(); assertEquals(listOf("a"),c.items); assertTrue(c.failed)
        fail=false; c.more(); assertEquals(listOf(1,2,2),calls); assertEquals(listOf("a","b"),c.items)
        assertFalse(c.hasMore); c.close(); scope.cancel()
    }
    @Test fun staleResponseCannotOverwriteRefresh()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val old=CompletableDeferred<Unit>(); var calls=0
        val c=DramaPageController(scope,{it:String->it}) { page ->
            val n=++calls; if(n==1) withContext(NonCancellable) { old.await() }
            DramaPage(listOf(if(n==1) "stale" else "fresh"),1,page,20)
        }
        c.reload(); c.reload(); old.complete(Unit); yield()
        assertEquals(listOf("fresh"),c.items); c.close(); scope.cancel()
    }
    @Test fun rankingShowsFourThenTenAndHandlesShortLists() {
        assertEquals(4,visibleRanking((1..10).toList(),false).size)
        assertEquals(10,visibleRanking((1..12).toList(),true).size)
        assertEquals(2,visibleRanking(listOf(1,2),false).size)
    }
}
