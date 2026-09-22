package com.vexora.app.ui.login

import android.content.Context
import android.content.res.Resources
import android.telephony.TelephonyManager
import com.vexora.core.region.LocalRegionSource
import com.vexora.core.region.LocalRegion
import com.vexora.core.region.LocalRegionKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 仅读默认 SIM 运营商国家及系统区域，不申请电话或定位权限，不按语言猜测国家。 */
class DeviceRegionSource @Inject constructor(@ApplicationContext private val context: Context) : LocalRegionSource {
    override fun countries(): List<LocalRegion> = buildList {
        runCatching { context.getSystemService(TelephonyManager::class.java)?.simCountryIso }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { add(LocalRegion(LocalRegionKind.SIM, it)) }
        runCatching { Resources.getSystem().configuration.locales[0]?.country }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { add(LocalRegion(LocalRegionKind.SYSTEM, it)) }
    }
}
