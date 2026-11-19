package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.*

class DramaPreviewWarmupControllerTest {
    private val expiry="2030-01-01T00:00:00Z"
    private fun entry(id:String,index:Int)=HomeFeedEntry(HomeFeedItem(id,"short_drama",id,id,"",true,
        preview=HomeFeedPreview("/preview/$id",expiry)),"batch",index)
    private class Gateway:HomeFeedGateway {
        var details=mutableListOf<String>();var resolvers=mutableListOf<String>()
        var hold:CompletableDeferred<Unit>?=null
        override suspend fun shortDramaDetail(dramaId:String):ShortDramaDetail {
            details+=dramaId;withContext(NonCancellable){hold?.await()}
            return ShortDramaDetail(dramaId,dramaId,"",emptyList(),listOf(ShortDramaEpisode("ep",1,"",5,true,false,"VIP_REQUIRED")),1)
        }
        override suspend fun resolveDramaPreview(resolverUrl:String):ResolvedDramaPreview {
            resolvers+=resolverUrl;return ResolvedDramaPreview("https://cdn.test/preview.mp4","2030-01-01T00:00:00Z")
        }
        override suspend fun homeFeed(requestId:String):HomeFeedBatch=error("Prefetch must not request Feed")
        override suspend fun acknowledgeHomeFeed(batchId:String,displayedThroughIndex:Int,idempotencyKey:String)=error("Prefetch must not ACK")
    }
    @Test fun neighborRequestsAreReusedWhenItBecomesCurrentAndWindowStaysBounded() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val g=Gateway()
        val c=DramaPreviewWarmupController(g,scope,now={0})
        val entries=(0..5).map{entry("d$it",it)}
        c.setWindow(entries,1);assertEquals(setOf("d0","d1","d2"),g.details.toSet())
        val ready=c.states[entries[2].key]!!;assertNotNull(ready.detail);assertNotNull(ready.media)
        c.setWindow(entries,2);assertSame(ready,c.states[entries[2].key]);assertEquals(1,g.resolvers.count{it=="/preview/d2"})
        assertEquals(3,c.states.size);assertNull(c.states[entries[0].key]);c.close();scope.cancel()
    }
    @Test fun reusedWarmupIsDiscardedWhenAuthorizationExpiresBeforeEntry() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val g=Gateway();var now=0L
        val c=DramaPreviewWarmupController(g,scope,now={now});val e=entry("d",0)
        c.setWindow(listOf(e),0);assertNotNull(c.states[e.key]!!.media)
        now=Long.MAX_VALUE;c.setWindow(listOf(e),0)
        assertNull(c.states[e.key]!!.media);assertTrue(c.states[e.key]!!.expired)
        c.close();scope.cancel()
    }
    @Test fun closedOrEvictedRequestsCannotPublishLateResponses()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val g=Gateway().apply{hold=CompletableDeferred()}
        val c=DramaPreviewWarmupController(g,scope,now={0});val entries=(0..5).map{entry("d$it",it)}
        c.setWindow(entries,0);val stale=c.states[entries[0].key]!!
        c.setWindow(entries,5);g.hold!!.complete(Unit);yield()
        assertNull(stale.detail);assertNull(c.states[entries[0].key]);c.close();assertTrue(c.states.isEmpty());scope.cancel()
    }
    @Test fun expiredGrantIsNotResolvedAndFailedWarmupCanRetry() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val g=Gateway()
        val c=DramaPreviewWarmupController(g,scope,now={Long.MAX_VALUE})
        val e=entry("d",0);c.setWindow(listOf(e),0)
        assertTrue(c.states[e.key]!!.expired);assertTrue(g.resolvers.isEmpty());assertNull(c.states[e.key]!!.media)
        c.close();scope.cancel()
    }
}
