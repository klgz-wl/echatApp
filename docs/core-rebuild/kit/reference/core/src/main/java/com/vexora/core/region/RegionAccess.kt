package com.vexora.core.region

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/** 地区限制只由应用层为指定变体启用，Core 不读取 BuildConfig。 */
data class RegionAccessConfiguration(val enabled: Boolean, val blockedCountries: Set<String>,
    val ipRetryAttempts: Int, val ipRetryDelayMillis: Long) {
    init { require(ipRetryAttempts >= 0 && ipRetryDelayMillis >= 0) }
}
enum class LocalRegionKind { SIM, SYSTEM }
data class LocalRegion(val kind: LocalRegionKind, val country: String)
fun interface LocalRegionSource { fun countries(): List<LocalRegion> }
fun interface IpRegionSource { suspend fun country(): String }
fun interface RegionNetworkSource { fun connected(): Boolean }
class RegionRestrictedException(val diagnosticCode: String) : Exception()
class RegionUnavailableException(val diagnosticCode: String = "R299") : Exception()
class RegionOfflineException : Exception() { val diagnosticCode = "R200" }

/** 稳定诊断编号不依赖异常类名或混淆名称，不向界面传递地址、响应正文或原始异常。 */
internal fun regionLookupFailure(error: Exception): RegionUnavailableException = when (error) {
    is RegionUnavailableException -> error
    is java.net.UnknownHostException -> RegionUnavailableException("R201")
    is javax.net.ssl.SSLException -> RegionUnavailableException("R203")
    is java.io.InterruptedIOException -> RegionUnavailableException("R202")
    is java.io.IOException -> RegionUnavailableException("R204")
    else -> RegionUnavailableException()
}

/** 每次启动重新检查，不持久缓存放行结果；任一本地信号命中可直接拦截。 */
class RegionAccess(private val configuration: RegionAccessConfiguration,
    private val local: LocalRegionSource, private val ip: IpRegionSource,
    private val network: RegionNetworkSource) {
    suspend fun check() {
        if (!configuration.enabled) return
        val blocked = configuration.blockedCountries.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val localHit = local.countries().firstOrNull { it.country.trim().uppercase(Locale.ROOT) in blocked }
        if (localHit != null) throw RegionRestrictedException(
            if (localHit.kind == LocalRegionKind.SIM) "R101" else "R102")
        val country = queryCountry()
        if (country in blocked) throw RegionRestrictedException("R103")
    }

    /** 只重试未获得有效国家码的查询；有效但受限的 IP 由调用方立即拒绝。 */
    private suspend fun queryCountry(): String {
        var retries = 0
        while (true) {
            requireNetwork()
            try {
                return ip.country().trim().uppercase(Locale.ROOT).also {
                    if (it !in Locale.getISOCountries()) throw RegionUnavailableException("R207")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // 请求中途断网立即结束本轮，不继续等待或消耗 IP 查询次数。
                requireNetwork()
                if (retries >= configuration.ipRetryAttempts) throw regionLookupFailure(error)
                retries++
                delay(configuration.ipRetryDelayMillis)
            }
        }
    }

    private fun requireNetwork() { if (!network.connected()) throw RegionOfflineException() }
}
