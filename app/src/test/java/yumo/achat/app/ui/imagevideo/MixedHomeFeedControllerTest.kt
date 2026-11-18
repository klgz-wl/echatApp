package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.*

class MixedHomeFeedControllerTest {
    private class Fake : HomeFeedGateway {
        val requests = mutableListOf<String>()
        val acks = mutableListOf<Triple<String, Int, String>>()
        var getFailures = 0
        var ackFailures = 0
        var batch = HomeFeedBatch("batch", 0, listOf(item(), item()))
        var hold: CompletableDeferred<Unit>? = null
        var successes = 0
        var invalidations = 0
        override suspend fun homeFeed(requestId: String): HomeFeedBatch {
            requests += requestId
            withContext(NonCancellable) { hold?.await() }
            if (invalidations-- > 0) throw HomeFeedServiceException("BATCH_INVALIDATED", 409)
            if (getFailures-- > 0) error("offline")
            return if (successes++ == 0) batch else batch.copy(items = emptyList())
        }
        override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) {
            acks += Triple(batchId, displayedThroughIndex, idempotencyKey)
            if (ackFailures-- > 0) error("offline")
        }
        override suspend fun resolveDramaPreview(resolverUrl: String): ResolvedDramaPreview = error("unused")
        override suspend fun shortDramaDetail(dramaId: String): ShortDramaDetail = error("unused")
    }
    private fun controller(fake: Fake, scope: CoroutineScope): MixedHomeFeedController {
        var sequence = 0
        return MixedHomeFeedController(fake, scope, { "id-${++sequence}" })
    }
    @Test fun `home preserves all kinds and original absolute indices`() = runBlocking {
        val fake = Fake().apply { batch = HomeFeedBatch("batch", 5, listOf(
            item().copy(kind = "video_template"), item().copy(kind = "live_persona"),
            item().copy(kind = "image_template"), item().copy(kind = "short_drama"), item().copy(kind = "unknown"))) }
        val c = controller(fake, this)
        c.start(); yield()
        assertEquals(
            listOf("video_template", "live_persona", "image_template", "short_drama", "unknown"),
            c.entries.map { it.item.kind },
        )
        assertEquals(listOf(5, 6, 7, 8, 9), c.entries.map { it.absoluteIndex })
        c.entries.forEach { c.displayed(it.key); yield() }
        assertEquals(9, fake.acks.last().second)
        c.close()
    }

    @Test fun `offset is absolute and only contiguous displayed prefix is acknowledged`() = runBlocking {
        val fake = Fake().apply { batch = batch.copy(offset = 5) }
        val controller = controller(fake, this)
        controller.start(); yield()
        controller.displayed("batch:6"); yield()
        assertTrue(fake.acks.isEmpty())
        controller.displayed("batch:5"); yield()
        assertEquals(6, fake.acks.first().second)
        controller.close()
    }
    @Test fun `first resumed item acknowledges offset five`() = runBlocking {
        val fake = Fake().apply { batch = batch.copy(offset = 5) }
        val controller = controller(fake, this)
        controller.start(); yield(); controller.displayed("batch:5"); yield()
        assertEquals(5, fake.acks.single().second)
        controller.close()
    }
    @Test fun `ack retry preserves index and key then next batch gets new request id`() = runBlocking {
        val fake = Fake().apply { ackFailures = 1; batch = batch.copy(items = listOf(item())) }
        val controller = controller(fake, this)
        controller.start(); yield(); controller.displayed("batch:0"); yield()
        assertTrue(controller.error)
        controller.retry(); yield()
        assertEquals(fake.acks[0], fake.acks[1])
        assertEquals(2, fake.requests.size)
        assertNotEquals(fake.requests[0], fake.requests[1])
        controller.close()
    }
    @Test fun `get retry preserves request id and empty feed stops`() = runBlocking {
        val fake = Fake().apply { getFailures = 1; batch = batch.copy(items = emptyList()) }
        val controller = controller(fake, this)
        controller.start(); yield(); controller.retry(); yield(); controller.start(); yield()
        assertEquals(2, fake.requests.size)
        assertEquals(fake.requests[0], fake.requests[1])
        assertTrue(controller.exhausted)
        controller.refresh(); yield()
        assertNotEquals(fake.requests[1], fake.requests[2])
        controller.close()
    }
    @Test fun `close discards late response`() = runBlocking {
        val fake = Fake().apply { hold = CompletableDeferred() }
        val controller = controller(fake, this)
        controller.start(); yield(); controller.close(); fake.hold!!.complete(Unit); yield()
        assertTrue(controller.entries.isEmpty())
    }
    @Test fun `invalidation automatically retries once then waits for explicit retry`() = runBlocking {
        val fake = Fake().apply { invalidations = 3 }
        val controller = controller(fake, this)
        controller.start(); yield(); yield()
        assertEquals(2, fake.requests.size)
        assertTrue(controller.error)
        assertNotEquals(fake.requests[0], fake.requests[1])
        controller.retry(); yield(); yield()
        assertEquals(4, fake.requests.size)
        assertFalse(controller.error)
        controller.close()
    }
    @Test fun `owner change discards late response`() = runBlocking {
        val fake = Fake().apply { hold = CompletableDeferred() }
        var owner = true
        val controller = MixedHomeFeedController(fake, this, isOwner = { owner })
        controller.start(); yield(); owner = false; fake.hold!!.complete(Unit); yield(); yield()
        assertTrue(controller.entries.isEmpty())
        assertFalse(controller.loading)
    }
    companion object {
        private fun item() = HomeFeedItem("same", "video_template", "same", "Title", "cover", false)
    }
}
