package com.vexora.app.ui.catalog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.catalog.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class CatalogUiState(val loading: Boolean = true, val failed: Boolean = false,
    val catalog: TemplateCatalog? = null, val selectedCategory: String? = null,
    val loadingMore: Boolean = false, val moreFailed: Boolean = false) {
    val templates: List<Template> get() = catalog?.filtered(selectedCategory).orEmpty()
}
@HiltViewModel
class CatalogViewModel @Inject constructor(private val repository: TemplateRepository,
    @Named("mockCatalog") private val mock: TemplateRepository,
    private val sessions: SessionCoordinator, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(CatalogUiState(selectedCategory = saved["category"]))
    val state = mutable.asStateFlow()
    private var channel: CatalogChannel? = null
    private var preview = false
    private var epoch: String? = sessions.current?.epoch
    private var request: kotlinx.coroutines.Job? = null
    private var version = 0L
    init {
        viewModelScope.launch {
            sessions.state.map { it?.epoch }.distinctUntilChanged().collect { value ->
                if (!preview && value != epoch) {
                    epoch = value; version++; request?.cancel(); saved.remove<String>("category")
                    mutable.value = CatalogUiState()
                    if (channel != null && value != null) reload()
                }
            }
        }
    }
    fun open(value: CatalogChannel, designPreview: Boolean) {
        if (channel != value || preview != designPreview) { channel = value; preview = designPreview; reload() }
    }
    fun select(category: String) {
        val current = mutable.value
        if (category == current.selectedCategory || current.catalog?.categories?.none { it.id == category } != false) return
        saved["category"] = category
        mutable.value = current.copy(selectedCategory = category)
        if (!preview) { request?.cancel(); reload() }
    }
    fun reload() { request?.cancel(); fetch(page = 1) }
    fun more() {
        val current = mutable.value
        if (request?.isActive == true || current.catalog?.hasMore != true) return
        fetch(current.catalog.page + 1)
    }
    private fun fetch(page: Int) {
        val value = channel ?: return
        val revision = ++version
        val owner = sessions.current?.epoch
        val category = mutable.value.selectedCategory
        mutable.value = if (page == 1) mutable.value.copy(loading = true, failed = false, moreFailed = false)
            else mutable.value.copy(loadingMore = true, moreFailed = false)
        request = viewModelScope.launch {
            try {
                val result = (if (preview) mock else repository).load(value, category, page)
                if (revision != version || (!preview && sessions.current?.epoch != owner)) return@launch
                val selected = category?.takeIf { id -> result.categories.any { it.id == id } }
                    ?: result.categories.firstOrNull()?.id
                saved["category"] = if (page == 1) selected else category
                val catalog = if (page == 1) result else result.copy(categories = mutable.value.catalog?.categories.orEmpty(),
                    templates = (mutable.value.catalog?.templates.orEmpty() + result.templates).distinctBy { it.id })
                mutable.value = CatalogUiState(loading = false, catalog = catalog, selectedCategory = if (page == 1) selected else category)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == version && (preview || sessions.current?.epoch == owner)) mutable.value =
                    if (page == 1) mutable.value.copy(loading = false, failed = true) else mutable.value.copy(loadingMore = false, moreFailed = true)
            }
        }
    }
}
