package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ShortMenuAccessControllerTest {
    private class Clock {
        var now=0L
        val waits=Channel<Long>(Channel.UNLIMITED)
        val ticks=Channel<Unit>(Channel.UNLIMITED)
        suspend fun wait(ms:Long) {waits.send(ms);ticks.receive();now+=ms}
        suspend fun step() {withTimeout(500){assertEquals(3000L,waits.receive());ticks.send(Unit);yield()}}
    }
    @Test fun bStartsVisibleWithoutPollingAndARevealsAtFirstConfirmedB()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val c=ShortMenuAccessController(scope,{calls++;true},{clock.now},clock::wait)
        c.setForeground(true);c.bind("B",true);assertTrue(c.isB);assertEquals(0,calls)
        c.bind("A",false);assertFalse(c.isB);assertEquals(0,calls)
        clock.step();assertTrue(c.isB);assertEquals(1,calls)
        c.bind("A",false);assertTrue(c.isB);assertEquals(1,calls)
        c.clear();scope.cancel()
    }
    @Test fun aPollingIsBoundedToOneMinuteAndDoesNotRestartOnRebind()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val times=mutableListOf<Long>()
        val c=ShortMenuAccessController(scope,{times+=clock.now;false},{clock.now},clock::wait)
        c.setForeground(true);c.bind("A",false)
        repeat(19){clock.step()}
        assertEquals((1L..19L).map {it*3000},times);assertFalse(c.isB)
        c.bind("A",false);assertEquals(19,times.size);assertTrue(clock.waits.tryReceive().isFailure)
        c.clear();scope.cancel()
    }
    @Test fun networkErrorsAndMissingFlagsDoNotShowShortAndBackgroundDoesNotFetch()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val c=ShortMenuAccessController(scope,{when(++calls){1->throw IOException();2->null;else->true}},{clock.now},clock::wait)
        c.bind("A",false);clock.step();assertEquals(0,calls)
        c.setForeground(true);clock.step();assertFalse(c.isB);clock.step();assertFalse(c.isB)
        clock.step();assertTrue(c.isB);assertEquals(3,calls)
        c.clear();scope.cancel()
    }
    @Test fun obsoleteProfileResponsesAndOtherAccountsCannotOverrideCurrentSide()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=ShortMenuAccessController(scope,{false},{clock.now},clock::wait)
        c.bind("A",false);val old=c.revision
        c.accept("A",true,old);assertTrue(c.isB)
        c.accept("A",false,old);assertTrue(c.isB)
        c.bind("other",false);c.accept("A",true);assertFalse(c.isB)
        c.accept("other",true);assertTrue(c.isB);c.accept("other",false);assertFalse(c.isB)
        c.clear();scope.cancel()
    }
    @Test fun requestFinishingAfterDeadlineCannotEnableShort()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val held=CompletableDeferred<Boolean>()
        val c=ShortMenuAccessController(scope,{held.await()},{clock.now},clock::wait)
        c.setForeground(true);c.bind("A",false);clock.step()
        clock.now=60_001;held.complete(true);yield();assertFalse(c.isB)
        c.bind("A",false);assertTrue(clock.waits.tryReceive().isFailure)
        c.clear();scope.cancel()
    }
    @Test fun stalledRequestTimesOutWithoutOverlappingOrChangingSide()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val c=ShortMenuAccessController(scope,{calls++;awaitCancellation()},{clock.now},clock::wait)
        c.setForeground(true);c.bind("A",false);clock.step()
        assertEquals(3000L,withTimeout(4500){clock.waits.receive()})
        assertEquals(1,calls);assertFalse(c.isB)
        c.clear();scope.cancel()
    }
    @Test fun backgroundConsumesWindowAndNavigationIdsDoNotShift()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var calls=0
        val c=ShortMenuAccessController(scope,{calls++;true},{clock.now},clock::wait)
        c.bind("A",false);repeat(19){clock.step()};c.setForeground(true)
        assertEquals(0,calls);assertFalse(c.isB);assertTrue(clock.waits.tryReceive().isFailure)
        assertEquals(0,resolvedBottomNavigationIndex(4,false));assertEquals(4,resolvedBottomNavigationIndex(4,true))
        listOf(0,1,2,3,5).forEach {assertEquals(it,resolvedBottomNavigationIndex(it,false))}
        c.clear();scope.cancel()
    }
    @Test fun accountSwitchDiscardsEvenUncancellableOldRequest()=runBlocking {
        val clock=Clock();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val held=CompletableDeferred<Boolean>()
        val c=ShortMenuAccessController(scope,{withContext(NonCancellable){held.await()}},{clock.now},clock::wait)
        c.setForeground(true);c.bind("old",false);clock.step()
        c.bind("new",false);held.complete(true);yield();assertFalse(c.isB)
        c.clear();scope.cancel()
    }
}
