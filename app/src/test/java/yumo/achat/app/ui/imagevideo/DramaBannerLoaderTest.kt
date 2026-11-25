package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.ShortDramaDetail

class DramaBannerLoaderTest {
    private fun detail(id:String)=ShortDramaDetail(id,id,"https://cdn.test/$id.jpg",emptyList(),emptyList(),0)
    @Test fun deduplicatesAndReusesSuccessfulDetails() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<String>()
        val loader=DramaBannerLoader(scope){id->calls+=id;detail(id)}
        loader.load(listOf("a","b","a"));loader.load(listOf("b","a"))
        assertEquals(listOf("a","b"),calls)
        assertEquals("https://cdn.test/a.jpg",loader.states["a"]!!.detail!!.posterUrl)
        loader.close();scope.cancel()
    }
    @Test fun limitsConcurrencyAndDiscardsLateCompletionAfterClose()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val hold=CompletableDeferred<Unit>();var calls=0
        val loader=DramaBannerLoader(scope){id->calls++;withContext(NonCancellable){hold.await()};detail(id)}
        loader.load(listOf("a","b","c","d"))
        assertEquals(2,calls)
        val state=loader.states.getValue("a")
        loader.close();hold.complete(Unit);yield()
        assertNull(state.detail);assertEquals(2,calls)
        scope.cancel()
    }
    @Test fun imageRetryUsesMetadataAndInFlightRetryDoesNotDuplicateWork()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val hold=CompletableDeferred<Unit>();var calls=0
        val loader=DramaBannerLoader(scope){id->calls++;hold.await();detail(id)}
        loader.load(listOf("a"));loader.retry("a");assertEquals(1,calls)
        hold.complete(Unit);yield()
        val state=loader.states.getValue("a");state.failed=true
        loader.retry("a");assertFalse(state.failed);assertEquals(1,calls)
        assertEquals(1,state.revision);loader.close();scope.cancel()
    }
    @Test fun retryPreservesOtherPostersAndDoesNotRepeatInFlightWork() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val loader=DramaBannerLoader(scope){id->if(id=="a" && calls++==0)error("offline");detail(id)}
        loader.load(listOf("a","b"));assertTrue(loader.states.getValue("a").failed)
        val other=loader.states.getValue("b").detail
        loader.retry("a");assertFalse(loader.states.getValue("a").failed)
        assertNotNull(loader.states.getValue("a").detail);assertSame(other,loader.states.getValue("b").detail)
        loader.close();scope.cancel()
    }
}
