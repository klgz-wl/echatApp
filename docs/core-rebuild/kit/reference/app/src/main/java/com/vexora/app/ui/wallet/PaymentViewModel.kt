package com.vexora.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.core.payment.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaymentViewModel @Inject constructor(private val coordinator: PaymentCoordinator, router: PurchaseRouter) : ViewModel() {
    val state = coordinator.state
    val failures = router.failures
    val displayMs = coordinator.configuration.successDisplayMs
    suspend fun opened(key: String): Boolean {
        coordinator.engine.opened(key)
        val current = state.value
        return current.record?.key == key && current.record?.openedAt != null && current.checkoutVisible && !current.storageFailed
    }
    fun pageEvent(key: String, type: String) = coordinator.pageEvent(key, type)
    fun close(key: String) = coordinator.close(key)
    fun retry(key: String) = coordinator.retry(key)
    suspend fun shown(key: String) = coordinator.engine.shown(key)
    suspend fun acknowledge(key: String) = coordinator.engine.acknowledge(key)
    fun refreshWallet() { viewModelScope.launch { coordinator.refreshWallet() } }
}
