package com.vexora.app.ui.generation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.visual.UploadedPhoto
import com.zorv.core.visual.UploadedPhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UploadedPhotosState(val photos: List<UploadedPhoto> = emptyList(), val busy: Boolean = false,
    val failed: Boolean = false, val page: Int = 0, val hasMore: Boolean = false)
@HiltViewModel
class UploadedPhotosViewModel @Inject constructor(private val repository: UploadedPhotoRepository,
    private val sessions: SessionCoordinator) : ViewModel() {
    private val mutable = MutableStateFlow(UploadedPhotosState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        viewModelScope.launch {
            sessions.state.map { it?.epoch }.distinctUntilChanged().collect {
                job?.cancel(); mutable.value = UploadedPhotosState()
            }
        }
    }
    fun load(refresh: Boolean = false) {
        if (state.value.busy || (!refresh && state.value.page > 0 && !state.value.hasMore)) return
        val session = sessions.current ?: return
        val page = if (refresh) 1 else state.value.page + 1
        mutable.update { if (refresh) UploadedPhotosState(busy = true) else it.copy(busy = true, failed = false) }
        job = viewModelScope.launch {
            try {
                val result = repository.photos(page)
                if (sessions.current?.epoch == session.epoch) mutable.update { it.copy(
                    photos = (it.photos + result.items).distinctBy { photo -> photo.id }, page = page,
                    hasMore = result.items.isNotEmpty() && page.toLong() * result.pageSize < result.total) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (sessions.current?.epoch == session.epoch) mutable.update { it.copy(failed = true) } }
            finally { if (sessions.current?.epoch == session.epoch) mutable.update { it.copy(busy = false) } }
        }
    }
}
