package yumo.achat.app.ui.imagevideo

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import yumo.achat.core.billing.SubscriptionPurchaseController

@HiltViewModel
internal class SubscriptionViewModel @Inject constructor(
    val purchases: SubscriptionPurchaseController,
) : ViewModel() {
    fun buy(activity: Activity, productId: String, sku: String, period: String, trigger: String, isVip: Boolean?) {
        viewModelScope.launch { purchases.buy(activity, productId, sku, period, trigger, isVip) }
    }
    fun restore() { purchases.onForeground() }
}
