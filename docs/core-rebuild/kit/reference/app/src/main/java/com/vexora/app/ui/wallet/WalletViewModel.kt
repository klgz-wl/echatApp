package com.vexora.app.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.wallet.*
import com.zorv.core.billing.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WalletUiState(val wallet: WalletSnapshot = WalletSnapshot(), val balanceBusy: Boolean = false,
    val productsBusy: Boolean = false, val recordsBusy: Boolean = false,
    val balanceError: Throwable? = null, val productsError: Throwable? = null, val recordsError: Throwable? = null,
    val payment: com.zorv.core.payment.PaymentViewState = com.zorv.core.payment.PaymentViewState(),
    val selectedId: String? = null, val purchase: CoinPurchaseState = CoinPurchaseState())

@HiltViewModel
class WalletViewModel @Inject constructor(private val repository: WalletRepository,
    private val sessions: SessionCoordinator,
    private val purchases: CoinPurchaseController, private val payments: com.zorv.core.payment.PaymentCoordinator, val displayConfig: CoinDisplayConfiguration) : ViewModel() {
    private val mutable = MutableStateFlow(WalletUiState())
    val state = mutable.asStateFlow()
    private var lastRecordsRefresh = false
    init {
        viewModelScope.launch {
            sessions.state.map { it?.epoch }.distinctUntilChanged().collectLatest { epoch ->
                mutable.value = WalletUiState()
                if (epoch == null) repository.clear() else refreshBalance()
            }
        }
        viewModelScope.launch {
            combine(purchases.state, payments.state, sessions.state) { value, payment, session ->
                val owned = payment.takeIf { it.epoch == session?.epoch } ?: com.zorv.core.payment.PaymentViewState()
                val official = if (value.epoch == session?.epoch) value else CoinPurchaseState()
                val purchase = if (owned.busy) official.copy(status = CoinPurchaseStatus.BUSY) else official
                owned to purchase
            }.collect { (payment, purchase) -> mutable.update { it.copy(purchase = purchase, payment = payment) } }
        }
        viewModelScope.launch {
            combine(repository.state, sessions.state) { data, session ->
                if (session != null && data.epoch == session.epoch) data.copy(products = data.products.filter { it.canDisplay }) else WalletSnapshot()
            }.collect { data -> mutable.update { it.copy(wallet = data) } }
        }
    }
    fun refreshBalance() {
        if (mutable.value.balanceBusy) return
        val epoch = sessions.current?.epoch ?: return
        mutable.update { it.copy(balanceBusy = true, balanceError = null) }
        viewModelScope.launch {
            try { repository.refreshBalance() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (sessions.current?.epoch == epoch) mutable.update { it.copy(balanceError = error) } }
            finally { if (sessions.current?.epoch == epoch) mutable.update { it.copy(balanceBusy = false) } }
        }
    }
    fun loadProducts() {
        if (mutable.value.productsBusy) return
        val epoch = sessions.current?.epoch ?: return
        mutable.update { it.copy(productsBusy = true, productsError = null) }
        viewModelScope.launch {
            try {
                repository.refreshProducts()
                val data = repository.state.value.copy(products = repository.state.value.products.filter { it.canDisplay })
                if (sessions.current?.epoch != epoch) return@launch
                mutable.update { it.copy(selectedId = it.selectedId?.takeIf { id -> data.products.any { p -> p.id == id } } ?: data.products.firstOrNull()?.id) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (sessions.current?.epoch == epoch) mutable.update { it.copy(productsError = error) } }
            finally { if (sessions.current?.epoch == epoch) mutable.update { it.copy(productsBusy = false) } }
        }
    }
    fun records(refresh: Boolean = false) {
        if (mutable.value.recordsBusy) return
        val epoch = sessions.current?.epoch ?: return
        lastRecordsRefresh = refresh
        mutable.update { it.copy(recordsBusy = true, recordsError = null) }
        viewModelScope.launch {
            try { repository.loadRecords(refresh) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (sessions.current?.epoch == epoch) mutable.update { it.copy(recordsError = error) } }
            finally { if (sessions.current?.epoch == epoch) mutable.update { it.copy(recordsBusy = false) } }
        }
    }
    fun retryRecords() = records(lastRecordsRefresh)
    fun select(id: String) { mutable.update { it.copy(selectedId = id) } }
}
