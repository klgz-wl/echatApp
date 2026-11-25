package yumo.achat.app.ui.imagevideo

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreparedDramaSubtitleTest {
    @Test fun vttSummaryUsesActualCueTimingsAndIgnoresCueSettings() {
        val summary=summarizeDramaWebVtt("""WEBVTT

00:01.250 --> 00:03.500 line:90%
First caption

00:00:04.000 --> 00:00:06.100
Second caption
""".toByteArray())
        assertEquals(2,summary.cueCount)
        assertEquals(1250L,summary.firstCueMs)
        assertEquals(6100L,summary.lastCueEndMs)
    }
    @Test fun summaryRejectsReverseRangesAndDoesNotInventTimings() {
        val summary=summarizeDramaWebVtt("WEBVTT\n\n00:10.000 --> 00:01.000\nBad\n".toByteArray())
        assertEquals(0,summary.cueCount);assertNull(summary.firstCueMs);assertNull(summary.lastCueEndMs)
    }
    @Test fun boundedReadReturnsBytesAndAlwaysClosesInput()=runBlocking {
        var closed=false
        val input=object:ByteArrayInputStream("WEBVTT\n".toByteArray()){override fun close(){closed=true;super.close()}}
        assertEquals("WEBVTT\n",readPreparedSubtitleBytes(input,32).decodeToString())
        assertTrue(closed)
    }
    @Test fun oversizedSubtitleFailsAndClosesWithoutKeepingPartialResult()=runBlocking {
        var closed=false
        val input=object:ByteArrayInputStream(ByteArray(33)){override fun close(){closed=true;super.close()}}
        try {readPreparedSubtitleBytes(input,32);fail("Expected bounded-read failure")}catch(_:IOException){}
        assertTrue(closed)
    }
    @Test fun cancellationClosesBlockedReadAndNeverPublishesLateResult()=runBlocking {
        val started=CountDownLatch(1);val released=CountDownLatch(1)
        var delivered=false
        val input=object:InputStream(){
            override fun read():Int {started.countDown();released.await(5,TimeUnit.SECONDS);return -1}
            override fun close(){released.countDown()}
        }
        val work=launch(Dispatchers.Default){readPreparedSubtitleBytes(input,32);delivered=true}
        assertTrue(withContext(Dispatchers.IO){started.await(3,TimeUnit.SECONDS)})
        work.cancelAndJoin();assertEquals(0L,released.count);assertFalse(delivered)
    }
}
