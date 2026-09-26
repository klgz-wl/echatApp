package com.zorv.core.integration.appsflyer

import java.lang.ref.WeakReference

/** 由主线程串行调用；仅弱持有已获业务准入的前台宿主，后台请求留到下一次前台。 */
internal class ForegroundAttributionStart<T : Any>(
    private val valid: (T) -> Boolean,
    private val start: (T) -> Boolean,
) {
    private var host = WeakReference<T>(null)
    private var requested = false
    private var submitted = false
    private var retryPending = false

    fun attach(value: T) { host = WeakReference(value); dispatch() }
    fun detach(value: T) { if (host.get() === value) host.clear() }
    fun request(retry: Boolean = false) {
        requested = true
        retryPending = retryPending || retry
        dispatch()
    }
    private fun dispatch() {
        if (!requested || (submitted && !retryPending)) return
        val current = host.get()?.takeIf(valid) ?: return
        retryPending = false
        submitted = start(current)
    }
}

enum class AttributionStartStage { NOT_REQUESTED, WAITING_FOR_ACTIVITY, START_CALLED, REQUEST_ACCEPTED, REQUEST_FAILED, INITIALIZATION_FAILED, DISABLED }
/** 仅保留阶段和 SDK 数值错误码，不保存可能含 URL／凭据的 SDK 原始错误文本。 */
data class AttributionStartStatus(val stage: AttributionStartStage, val errorCode: Int? = null)
