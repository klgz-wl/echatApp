package yumo.achat.app.ui.imagevideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import yumo.achat.core.backend.*

/** Keep server-ordered batches and ACK progress while billing recreates the Activity. */
internal class HomeFeedViewModel : ViewModel() {
    private var owner: String? = null
    private var gateway: HomeFeedGateway? = null
    private var controller: MixedHomeFeedController? = null
    private val currentGateway = object : HomeFeedGateway {
        override suspend fun shortDramaDetail(dramaId: String) = requireNotNull(gateway).shortDramaDetail(dramaId)
        override suspend fun homeFeed(requestId: String) = requireNotNull(gateway).homeFeed(requestId)
        override suspend fun acknowledgeHomeFeed(batchId: String, displayedThroughIndex: Int, idempotencyKey: String) =
            requireNotNull(gateway).acknowledgeHomeFeed(batchId, displayedThroughIndex, idempotencyKey)
        override suspend fun resolveDramaPreview(resolverUrl: String) = requireNotNull(gateway).resolveDramaPreview(resolverUrl)
    }
    fun bind(ownerKey: String, gateway: HomeFeedGateway): MixedHomeFeedController {
        if (owner != ownerKey || controller == null) {
            release()
            owner = ownerKey
            controller = MixedHomeFeedController(currentGateway, viewModelScope)
        }
        // New compositions supply their current authenticated gateway without reloading the batch.
        this.gateway = gateway
        return requireNotNull(controller)
    }
    fun releaseOwner(ownerKey: String) { if (owner == ownerKey) release() }
    private fun release() { controller?.close(); controller = null; gateway = null; owner = null }
    override fun onCleared() { release() }
}
