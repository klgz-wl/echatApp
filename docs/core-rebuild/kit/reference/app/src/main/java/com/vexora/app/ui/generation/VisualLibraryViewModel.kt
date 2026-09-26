package com.vexora.app.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.visual.*
import com.zorv.core.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VisualLibraryState(val requests: List<GenerationRequest> = emptyList(), val resources: List<VisualResource> = emptyList(),
    val loading: Boolean = false, val failed: Boolean = false, val pollingFailed: Boolean = false,
    val loadingMore: Boolean = false, val moreFailed: Boolean = false, val hasMore: Boolean = false, val nextPage: Int = 1,
    val modality: String? = null,
    val dismissedHomeNotices: Set<String> = emptySet()) {
    // 内存确认集覆盖正在返回的旧轮询快照；磁盘标记负责重启恢复。
    val homeNotice: GenerationRequest? get() = requests.lastOrNull {
        it.task?.status in setOf("processing", "succeeded", "failed") && !it.homeNoticeDismissed && it.key !in dismissedHomeNotices
    }

}

/** 根页面持有该 VM，轮询与当前导航页无关；离开前台只暂停查询，不取消后台任务。 */
@HiltViewModel
class VisualLibraryViewModel @Inject constructor(private val sessions: SessionCoordinator,
    private val tasks: GenerationRequestRepository, private val library: VisualLibraryRepository,
    private val wallet: WalletRepository, private val photos: PrivateGenerationStorage, val profile: ProfilePreview, private val config: VisualPollingConfiguration) : ViewModel() {
    private val mutable = MutableStateFlow(VisualLibraryState())
    val state = mutable.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private var pageJob: Job? = null
    private var pageVersion = 0L
    init {
        viewModelScope.launch {
            var epoch: String? = null
            combine(sessions.state.map { it?.epoch }.distinctUntilChanged(), foreground) { owner, active -> owner to active }
                .collectLatest { (owner, active) ->
                    if (owner != epoch) { pageJob?.cancel(); pageVersion++; mutable.value = VisualLibraryState(); epoch = owner }
                    if (!active || owner == null) return@collectLatest
                    refresh()
                    val due = mutableMapOf<String, Long>()
                    var walletNeedsRefresh = false
                    val failedTasks = mutableSetOf<String>()
                    while (currentCoroutineContext().isActive && sessions.current?.epoch == owner) {
                        try {
                            val user = sessions.current?.userId ?: break
                            var records = tasks.pending(user)
                            if (sessions.current?.epoch != owner) break
                            mutable.update { it.copy(requests = records) }
                            var changed = false
                            for (request in records) {
                                val previous = request.task ?: continue
                                val unsettled = previous.status == "processing" || (previous.status == "failed" && previous.diamondCost > 0 && !previous.refunded)
                                if (!unsettled || System.nanoTime() < (due[request.key] ?: 0L)) continue
                                var next = previous
                                try {
                                    next = tasks.refresh(request)
                                    failedTasks.remove(request.key)
                                    changed = changed || next.status != previous.status || next.refunded != previous.refunded
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failedTasks.add(request.key) }
                                due[request.key] = System.nanoTime() + config.interval(next) * 1_000_000_000L
                            }
                            records = tasks.pending(user)
                            if (sessions.current?.epoch != owner) break
                            mutable.update { it.copy(requests = records, pollingFailed = failedTasks.isNotEmpty() || walletNeedsRefresh) }
                            if (changed) { refresh(); walletNeedsRefresh = true }
                            if (walletNeedsRefresh) {
                                try { wallet.refreshBalance(); wallet.loadRecords(refresh = true); walletNeedsRefresh = false }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { mutable.update { it.copy(pollingFailed = true) } }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { if (sessions.current?.epoch == owner) mutable.update { it.copy(pollingFailed = true) } }
                        delay(config.minSeconds * 1000L)
                    }
                }
        }
    }
    fun sourcePath(request: GenerationRequest?) = request?.photo?.let { photos.photoFile(it).takeIf { file -> file.isFile }?.path }
    suspend fun openHomeNotice(request: GenerationRequest): GenerationRequest? {
        val epoch = sessions.current?.epoch ?: return null
        val acknowledged = tasks.acknowledgeHomeNotice(request, epoch) ?: return null
        if (sessions.current?.epoch != epoch) return null
        mutable.update { state -> state.copy(
            requests = state.requests.map { if (it.key == acknowledged.key) acknowledged else it },
            dismissedHomeNotices = if (acknowledged.homeNoticeDismissed) state.dismissedHomeNotices + acknowledged.key else state.dismissedHomeNotices
        ) }
        return acknowledged
    }
    fun active(value: Boolean) { if (!value) pageJob?.cancel(); foreground.value = value }
    fun filter(modality: String?) {
        if (modality !in listOf(null, "image", "video") || state.value.modality == modality) return
        mutable.value = state.value.copy(resources = emptyList(), modality = modality, hasMore = false, nextPage = 1)
        refresh()
    }
    fun refresh() = page(refresh = true)
    fun more() = page(refresh = false)
    private fun page(refresh: Boolean) {
        val session = sessions.current ?: return
        if (!refresh && (pageJob?.isActive == true || !state.value.hasMore)) return
        pageJob?.cancel()
        val version = ++pageVersion
        val page = if (refresh) 1 else state.value.nextPage
        val modality = state.value.modality
        mutable.update { it.copy(loading = refresh, loadingMore = !refresh, failed = false, moreFailed = false) }
        pageJob = viewModelScope.launch {
            try {
                val response = library.resources(page, modality)
                if (sessions.current?.epoch != session.epoch || version != pageVersion) return@launch
                mutable.update { it.copy(resources = ((if (refresh) emptyList() else it.resources) + response.items).distinctBy(VisualResource::id),
                    nextPage = page + 1, hasMore = response.items.isNotEmpty() && page.toLong() * response.pageSize < response.total) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (sessions.current?.epoch == session.epoch && version == pageVersion) mutable.update { it.copy(failed = refresh, moreFailed = !refresh) }
            } finally {
                if (sessions.current?.epoch == session.epoch && version == pageVersion) mutable.update { it.copy(loading = false, loadingMore = false) }
            }
        }
    }
}
