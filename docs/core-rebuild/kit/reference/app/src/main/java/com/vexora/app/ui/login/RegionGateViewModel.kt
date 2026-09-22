package com.vexora.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.core.region.RegionAccess
import com.vexora.core.region.RegionRestrictedException
import com.vexora.core.region.RegionUnavailableException
import com.vexora.core.region.RegionOfflineException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 地区入口不依赖认证、统计、支付或业务 Repository，拒绝时不构造后续依赖图。 */
data class RegionGateState(val allowed: Boolean = false, val busy: Boolean = false, val error: Throwable? = null)

@HiltViewModel
class RegionGateViewModel @Inject constructor(private val region: RegionAccess) : ViewModel() {
    private val mutable = MutableStateFlow(RegionGateState())
    val state = mutable.asStateFlow()
    fun start() { if (state.value.error == null) check() }
    fun check() {
        if (state.value.busy || state.value.allowed) return
        mutable.value = RegionGateState(busy = true)
        viewModelScope.launch {
            try {
                region.check()
                mutable.value = RegionGateState(allowed = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = RegionGateState(error = when (error) {
                    is RegionRestrictedException, is RegionUnavailableException, is RegionOfflineException -> error
                    else -> RegionUnavailableException()
                })
            }
        }
    }
}
