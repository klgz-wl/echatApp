package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.backend.*

class ShortDramaPlaybackControllerTest {
    private class Store : DramaUnlockStore {
        var value: DramaUnlockIntent? = null
        var sequence = 0
        var broken = false
        var clearBroken = false
        override fun pending(dramaId: String, episodeId: String, amount: Int): DramaUnlockIntent = if (broken) error("storage failure") else value ?: DramaUnlockIntent(dramaId, episodeId, amount, "key${++sequence}").also { value = it }
        override fun clear(intent: DramaUnlockIntent) { if (clearBroken) error("storage failure"); value = null }
    }
    private class Gateway : ShortDramaPlaybackGateway {
        var plays = 0; var refreshes = 0
        var nextReason = "allowed"
        var refreshFailure: String? = null
        var expiry = "2030-01-01T00:00:00Z"
        var mediaExpiry: String? = null
        var detailHeld: CompletableDeferred<Unit>? = null
        var unlockHeld: CompletableDeferred<Unit>? = null
        var subtitleExpiry: String? = null
        var subtitleFailure = false
        var amount: Int? = 5; var reason = "EPISODE_LOCKED"
        var unlocked = true; var failure: String? = null; var playFailure = false
        val keys = mutableListOf<String>()
        var held: CompletableDeferred<Unit>? = null
        override suspend fun shortDramaDetail(dramaId: String): ShortDramaDetail { detailHeld?.await(); return ShortDramaDetail(dramaId,"Drama","", emptyList(),listOf(ShortDramaEpisode("a",3,"A",amount,false,unlocked,reason),ShortDramaEpisode("b",4,"B",if(nextReason=="allowed")0 else 5,nextReason=="VIP_REQUIRED",nextReason=="allowed",nextReason)),2) }
        private fun grant(id: String) = DramaPlaybackGrant(id,id,"media/$id",expiry,listOf(DramaSubtitle("en","en","English","vtt","subtitle")))
        override suspend fun playDramaEpisode(dramaId: String, episodeId: String): DramaPlaybackGrant { plays++; if (playFailure) throw HomeFeedServiceException("HOME_NETWORK_ERROR"); if (episodeId == "a") held?.await(); return grant(episodeId) }
        override suspend fun refreshDramaGrant(grant: String): DramaPlaybackGrant { refreshes++; refreshFailure?.let { throw HomeFeedServiceException(it) }; return grant(grant) }
        override suspend fun resolveDramaMedia(resolverUrl: String) = ResolvedDramaPreview(resolverUrl,mediaExpiry ?: expiry)
        override suspend fun resolveDramaSubtitle(resolverUrl: String): ResolvedDramaPreview { if (subtitleFailure) error("subtitle failure"); return ResolvedDramaPreview(resolverUrl,subtitleExpiry ?: expiry) }
        override suspend fun unlockDramaEpisode(dramaId: String, episodeId: String, confirmedAmount: Int, idempotencyKey: String) { keys += idempotencyKey; unlockHeld?.await(); failure?.let { throw HomeFeedServiceException(it) }; unlocked = true }
        override suspend fun homeFeed(requestId: String): HomeFeedBatch = error("unused")
        override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) = Unit
        override suspend fun resolveDramaPreview(resolverUrl: String) = resolveDramaMedia(resolverUrl)
    }
    @Test fun automaticNextEpisodeWaitsForSubscriptionOrPaymentWithoutUnlocking() = runBlocking {
        for (reason in listOf("VIP_REQUIRED","EPISODE_LOCKED")) {
            val g=Gateway().apply {nextReason=reason};val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val c=ShortDramaPlaybackController(g,scope,Store());c.start("d");c.episodeEnded("a",c.grant!!.authorizationId)
            assertEquals("b",c.episode?.episodeId);assertEquals(1,g.plays);assertTrue(g.keys.isEmpty())
            assertEquals(if(reason=="VIP_REQUIRED")DramaPlaybackAction.VipRequired else DramaPlaybackAction.UnlockRequired,c.action)
            c.close();scope.cancel()
        }
    }
    @Test fun completionAdvancesActualEpisodeAndFinishesWithoutLoop() = runBlocking {
        val g=Gateway(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        c.episodeEnded("a", c.grant!!.authorizationId)
        assertEquals("b",c.episode?.episodeId); assertEquals(2,g.plays)
        c.episodeEnded("a","a"); assertEquals(2,g.plays)
        c.episodeEnded("b",c.grant!!.authorizationId)
        assertTrue(c.completed); assertFalse(c.playing); assertEquals(2,g.plays)
        c.close(); scope.cancel()
    }
    @Test fun sameDramaCanOpenExplicitHistoryEpisode() = runBlocking {
        val g=Gateway(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        c.start("d","b"); assertEquals("b",c.episode?.episodeId); assertEquals(2,g.plays)
        c.close(); scope.cancel()
    }
    @Test fun pausedBackgroundReturnDoesNotRenewUntilPlaybackResumes() = runBlocking {
        val g=Gateway(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=ShortDramaPlaybackController(g,scope,Store());c.start("d");c.setPlaying(false)
        c.setVisible(false);c.setVisible(true);assertEquals(0,g.refreshes)
        c.close();scope.cancel()
    }
    @Test fun pauseCancelsScheduledRefresh() = runBlocking {
        val g=Gateway(); val tick=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=ShortDramaPlaybackController(g,scope,Store(),wait={tick.await()})
        c.start("d"); c.setPlaying(false); tick.complete(Unit); yield()
        assertEquals(0,g.refreshes); c.close(); scope.cancel()
    }
    @Test fun resumeAndManualRefreshNeverReplay() = runBlocking {
        val g = Gateway(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store())
        c.start("d"); assertEquals("a",c.episode?.episodeId); assertEquals(1,g.plays)
        c.updatePosition(400,1000); c.setVisible(false); c.setVisible(true)
        c.retry(); assertEquals(1,g.plays); assertTrue(g.refreshes >= 1); assertEquals(400L,c.positionMs)
        c.close(); scope.cancel()
    }
    @Test fun switchingDiscardsOldEpisode() = runBlocking {
        val g = Gateway(); g.held = CompletableDeferred()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.selectEpisode("b"); g.held!!.complete(Unit)
        assertEquals("b",c.grant?.grant); c.close(); scope.cancel()
    }
    @Test fun unknownUnlockRetriesSameKey() = runBlocking {
        val g = Gateway().apply { unlocked = false; failure = "HOME_NETWORK_ERROR" }; val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,store); c.start("d"); c.confirmUnlock(); g.failure = null; c.retry()
        assertEquals(listOf("key1","key1"),g.keys); assertNull(store.value); assertEquals(1,g.plays); assertEquals(1L,c.unlockSuccessSerial); c.close(); scope.cancel()
    }
    @Test fun priceChangeRequiresNewConfirmation() = runBlocking {
        val g = Gateway().apply { unlocked = false; failure = "PRICE_CHANGED" }; val store = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,store); c.start("d"); c.confirmUnlock()
        assertNull(store.value); assertEquals(DramaPlaybackAction.UnlockRequired,c.action); assertEquals(1,g.keys.size); assertEquals(0,g.plays)
        g.failure = null; c.confirmUnlock(); assertEquals(listOf("key1","key2"),g.keys); assertEquals(1,g.plays)
        c.close(); scope.cancel()
    }
    @Test fun inactiveCancelsRenewal() = runBlocking {
        val g = Gateway(); val tick = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store(),wait = { tick.await() }); c.start("d"); c.setVisible(false); tick.complete(Unit)
        assertEquals(0,g.refreshes); c.close(); scope.cancel()
    }
    @Test fun unknownPlayDoesNotRetryOnResume() = runBlocking {
        val g = Gateway().apply { playFailure = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        c.setVisible(false); c.setVisible(true); assertEquals(1,g.plays)
        c.retry(); assertEquals(2,g.plays); c.close(); scope.cancel()
    }
    @Test fun timerRefreshesWithoutReplay() = runBlocking {
        val g = Gateway(); val ticks = kotlinx.coroutines.channels.Channel<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store(),wait = { ticks.receive() })
        c.start("d"); c.updatePosition(300,900); ticks.send(Unit)
        assertEquals(1,g.refreshes); assertEquals(1,g.plays); assertEquals(300L,c.positionMs)
        c.close(); scope.cancel()
    }

    @Test fun missingPriceCannotUnlockAndFreeAmountCannotBypassVip() = runBlocking {
        val g = Gateway().apply { unlocked = false; amount = null; reason = "PRICE_NOT_CONFIGURED" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.confirmUnlock()
        assertEquals(DramaPlaybackAction.Unavailable,c.action); assertTrue(g.keys.isEmpty()); assertEquals(0,g.plays)
        g.amount = 0; g.reason = "VIP_REQUIRED"; c.retry(); c.confirmUnlock()
        assertEquals(DramaPlaybackAction.VipRequired,c.action); assertTrue(g.keys.isEmpty()); assertEquals(0,g.plays)
        c.close(); scope.cancel()
    }

    @Test fun failedPendingWriteDoesNotPurchase() = runBlocking {
        val g = Gateway().apply { unlocked = false }; val store = Store().apply { broken = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,store); c.start("d"); c.confirmUnlock()
        assertEquals(DramaPlaybackAction.Retry,c.action); assertTrue(g.keys.isEmpty()); assertEquals(0,g.plays)
        c.close(); scope.cancel()
    }
    @Test fun malformedOrExpiredGrantIsNeverPublished() = runBlocking {
        for (expiry in listOf("invalid", "2000-01-01T00:00:00Z")) {
            val g = Gateway().apply { this.expiry = expiry }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d")
            assertNull(c.grant); assertNull(c.media); assertEquals(DramaPlaybackAction.Retry,c.action)
            c.close(); scope.cancel()
        }
    }
    @Test fun nonExtendingRefreshStopsWithManualRetryAndClearsMedia() = runBlocking {
        val g = Gateway(); val ticks = kotlinx.coroutines.channels.Channel<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val current = java.time.Instant.parse(g.expiry).toEpochMilli() - 10_000
        val c = ShortDramaPlaybackController(g,scope,Store(),now = { current },wait = { ticks.receive() })
        c.start("d"); assertNotNull(c.media); ticks.send(Unit)
        assertEquals(1,g.refreshes); assertEquals(1,g.plays); assertNull(c.media)
        assertNotNull(c.grant); assertEquals(DramaPlaybackAction.Retry,c.action)
        c.setVisible(false); c.setVisible(true); assertEquals(1,g.refreshes)
        c.close(); scope.cancel()
    }

    @Test fun failedClearKeepsOriginalPurchaseIntentForSafeRetry() = runBlocking {
        val g = Gateway().apply { unlocked = false }; val store = Store().apply { clearBroken = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,store); c.start("d"); c.confirmUnlock()
        assertEquals(DramaPlaybackAction.Retry,c.action); assertNotNull(store.value); assertEquals(0,g.plays)
        store.clearBroken = false; c.retry(); assertEquals(listOf("key1","key1"),g.keys); assertEquals(1,g.plays)
        c.close(); scope.cancel()
    }
    @Test fun expiredResolvedMediaIsNeverPublished() = runBlocking {
        val g = Gateway().apply { mediaExpiry = "2000-01-01T00:00:00Z" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        assertNull(c.media); assertNotNull(c.grant); assertEquals(DramaPlaybackAction.Retry,c.action)
        c.close(); scope.cancel()
    }

    @Test fun mediaFailureRetriesWithRefreshAndPreservesPosition() = runBlocking {
        val g = Gateway(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.updatePosition(450,900)
        c.reportMediaError()
        assertNull(c.media); assertNotNull(c.grant); assertFalse(c.playing)
        assertEquals("DRAMA_MEDIA_ERROR",c.errorCode); assertEquals(DramaPlaybackAction.Retry,c.action)
        c.retry(); assertEquals(1,g.plays); assertEquals(1,g.refreshes)
        assertTrue(c.playing); assertNotNull(c.media); assertEquals(450L,c.positionMs)
        c.setPlaying(false); c.setVisible(false); c.setVisible(true)
        assertFalse(c.playing)
        c.close(); scope.cancel()
    }

    @Test fun hiddenDetailCompletionDefersPlayUntilVisible() = runBlocking {
        val g = Gateway().apply { detailHeld = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.setVisible(false)
        g.detailHeld!!.complete(Unit); assertNotNull(c.episode); assertEquals(0,g.plays)
        c.setVisible(true); assertEquals(1,g.plays); c.close(); scope.cancel()
    }
    @Test fun subtitleSelectionAndRemovalRescheduleEarliestExpiry() = runBlocking {
        val g = Gateway().apply { subtitleExpiry = "2029-12-31T23:59:00Z" }
        val waits = mutableListOf<Long>(); val tick = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val current = java.time.Instant.parse(g.expiry).toEpochMilli() - 300_000
        val c = ShortDramaPlaybackController(g,scope,Store(),now = { current },wait = { waits += it; tick.await() })
        c.start("d"); assertEquals(280_000L,waits.last())
        c.selectSubtitle("en"); assertEquals(220_000L,waits.last()); assertNotNull(c.subtitleMedia)
        c.selectSubtitle(null); assertEquals(280_000L,waits.last()); assertNull(c.subtitleMedia)
        g.subtitleFailure = true; c.selectSubtitle("en"); assertNotNull(c.media); assertNotNull(c.errorCode)
        c.close(); scope.cancel()
    }

    @Test fun confirmedUnlockFinishesHiddenButDefersPlay() = runBlocking {
        val g = Gateway().apply { unlocked = false; unlockHeld = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.confirmUnlock(); c.setVisible(false)
        g.unlockHeld!!.complete(Unit); assertTrue(g.unlocked); assertEquals(0,g.plays)
        c.setVisible(true); assertEquals(1,g.plays); c.close(); scope.cancel()
    }

    @Test fun restoredVipRefreshesExistingGrantWithoutNewPlay() = runBlocking {
        val g = Gateway(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        g.refreshFailure = "VIP_REQUIRED"; c.retry()
        assertEquals(DramaPlaybackAction.VipRequired,c.action); assertNotNull(c.grant); assertNull(c.media)
        c.setVisible(false); g.refreshFailure = null; c.setVisible(true)
        assertEquals(1,g.plays); assertEquals(2,g.refreshes); assertNotNull(c.media)
        c.close(); scope.cancel()
    }
    @Test fun priceChangeToAlreadyAllowedStartsPlaybackWithoutAnotherPurchase() = runBlocking {
        val g = Gateway().apply { unlocked = false; failure = "PRICE_CHANGED"; unlockHeld = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d"); c.confirmUnlock()
        g.unlocked = true; g.unlockHeld!!.complete(Unit)
        assertEquals(1,g.keys.size); assertEquals(1,g.plays); assertNotNull(c.media)
        c.close(); scope.cancel()
    }

    @Test fun deniedGrantRequiresExplicitRetryForFreshPlayback() = runBlocking {
        val g = Gateway(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = ShortDramaPlaybackController(g,scope,Store()); c.start("d")
        g.refreshFailure = "GRANT_DENIED"; c.retry()
        assertNull(c.grant); assertNull(c.media); assertEquals(DramaPlaybackAction.Unavailable,c.action)
        c.setVisible(false); c.setVisible(true); assertEquals(1,g.plays); assertEquals(1,g.refreshes)
        g.refreshFailure = null; c.retry(); assertEquals(2,g.plays); assertNotNull(c.media)
        c.setPlaying(false); c.selectEpisode("b"); assertTrue(c.playing); assertEquals("b",c.episode?.episodeId)
        c.close(); scope.cancel()
    }

}
