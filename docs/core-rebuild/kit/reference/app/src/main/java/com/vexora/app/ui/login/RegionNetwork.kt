package com.vexora.app.ui.login

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vexora.core.region.RegionNetworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 仅检查地区请求的网络前提，不构造认证或其他业务依赖。 */
class RegionNetwork @Inject constructor(@ApplicationContext private val context: Context) : RegionNetworkSource {
    override fun connected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        // 系统联网验证可能被云手机环境阻断，不能单凭缺少 VALIDATED 就认定没有网络。
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }
}
