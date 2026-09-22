package com.vexora.app.ui.generation

import android.net.Uri
import com.vexora.core.analytics.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.core.auth.SessionCoordinator
import com.vexora.core.catalog.Template
import com.vexora.core.visual.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class QualityOption(val id: String, val label: String)
@Serializable data class GenerationOptions(val defaultQuality: String, val qualities: List<QualityOption>)
data class GenerationUiState(val request: GenerationRequest? = null, val template: Template? = null,
    val quality: String = "", val preparing: Boolean = false, val submitting: Boolean = false,
    val error: GenerationFailure? = null, val purchaseNeeded: Boolean = false) {
    val cost: Long? get() = template?.prices?.forQuality(quality)
    val canGenerate: Boolean get() = request != null && cost != null && !submitting && !preparing && request.task == null
}

@HiltViewModel
class GenerationViewModel @Inject constructor(private val sessions: SessionCoordinator,
    private val repository: GenerationRequestRepository, private val storage: PrivateGenerationStorage,
    private val downloads: UploadedPhotoDownloadApi,
    private val events: EventTracker, val options: GenerationOptions, private val saved: SavedStateHandle, private val json: Json) : ViewModel() {
    private val mutable = MutableStateFlow(GenerationUiState(quality = options.defaultQuality))
    val state = mutable.asStateFlow()
    private var preparationJob: kotlinx.coroutines.Job? = null
    private var selectionVersion = 0
    init {
        require(options.defaultQuality in options.qualities.map { it.id } && options.qualities.all { it.id in setOf("fast", "quality") })
        viewModelScope.launch {
            var observedEpoch: String? = null
            sessions.state.map { it?.epoch }.distinctUntilChanged().collect {
                if (it == null && observedEpoch == null) return@collect
                if (observedEpoch != null && observedEpoch != it) discard()
                observedEpoch = it
                val encoded = saved.get<String>("generation_draft")
                val draft = encoded?.let { runCatching { json.decodeFromString<GenerationRequest>(it) }.getOrNull() }
                if (draft != null && draft.userId == sessions.current?.userId) {
                    mutable.value = GenerationUiState(draft, draft.template, draft.quality, error = draft.failure)
                } else { mutable.value = GenerationUiState(quality = options.defaultQuality); saved["generation_draft"] = null }
            }
        }
    }
    fun open(template: Template, source: String = "unknown") {
        if (mutable.value.submitting || mutable.value.preparing) return
        mutable.value = GenerationUiState(template = template, quality = options.defaultQuality)
        saved["generation_source"] = source
        saved["generation_draft"] = null
        saved["generation_template"] = json.encodeToString(Template.serializer(), template)
    }
    fun restoreTemplate() {
        if (mutable.value.template == null) saved.get<String>("generation_template")?.let {
            runCatching { json.decodeFromString<Template>(it) }.getOrNull()?.let { template -> mutable.update { it.copy(template = template) } }
        }
    }
    fun select(uri: Uri?) {
        if (uri != null) preparePhoto("device") { user -> storage.prepare(uri, user) }
    }
    fun selectUploaded(resource: UploadedPhoto) = preparePhoto("uploaded_resource") { user ->
        if (!resource.media().canLoad) throw GenerationException(GenerationFailure.INVALID_IMAGE)
        downloads.download(resource.url).use { response -> response.byteStream().use { storage.prepare(it, user) } }
    }
    private fun preparePhoto(selectionSource: String, action: suspend (String) -> SourcePhoto) {
        if (state.value.preparing || state.value.submitting || state.value.request?.submitted == true) return
        val template = state.value.template ?: return
        val session = sessions.current ?: return
        val old = state.value.request
        val version = ++selectionVersion
        mutable.update { it.copy(preparing = true, error = null) }
        preparationJob = viewModelScope.launch {
            try {
                val photo = action(session.userId)
                if (sessions.current?.epoch != session.epoch || version != selectionVersion) { storage.discard(photo); return@launch }
                old?.photo?.let { storage.discard(it) }
                publish(GenerationRequest(userId = session.userId, template = template, quality = state.value.quality, photo = photo, source = saved["generation_source"] ?: "unknown"))
                events.track("upload_img_result", template.analyticsProperties() + mapOf("phase" to "local_prepare", "selection_source" to selectionSource, "is_success" to true), session.userId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (sessions.current?.epoch == session.epoch && version == selectionVersion) {
                mutable.update { it.copy(error = GenerationFailure.INVALID_IMAGE) }
                events.track("upload_img_result", template.analyticsProperties() + mapOf("phase" to "local_prepare", "selection_source" to selectionSource,
                    "is_success" to false, "fail_reason" to "invalid_image"), session.userId)
            } }
            finally { if (sessions.current?.epoch == session.epoch && version == selectionVersion) mutable.update { it.copy(preparing = false) } }
        }
    }
    fun uploadClicked() { state.value.template?.let { events.track("click_upload_img", it.analyticsProperties() + mapOf("source" to (saved.get<String>("generation_source") ?: "unknown"))) } }
    fun pickerUnavailable() { state.value.template?.let { events.track("upload_img_result", it.analyticsProperties() + mapOf("phase" to "local_prepare", "is_success" to false, "fail_reason" to "picker_unavailable")) } }
    fun quality(id: String) {
        if (mutable.value.submitting || mutable.value.request?.submitted == true || options.qualities.none { it.id == id }) return
        mutable.update { it.copy(quality = id) }; mutable.value.request?.let { publish(it.copy(quality = id)) }
    }
    fun generate() {
        val epoch = sessions.current?.epoch ?: return
        val current = mutable.value
        // 当前质量必须有真实模板报价，费用随草稿保存；最终扣费以任务响应为准。
        if (!current.canGenerate) return
        val request = current.request ?: return
        events.track("click_generate", request.template.analyticsProperties() + mapOf("request_id" to request.key, "quality" to request.quality, "diamond_cost" to current.cost!!, "source" to request.source), request.userId)
        mutable.update { it.copy(submitting = true, error = null, purchaseNeeded = false) }
        publish(request.copy(submitted = true))
        viewModelScope.launch {
            try {
                val task = repository.submit(request)
                if (sessions.current?.epoch == epoch && state.value.request?.key == request.key) publish(request.copy(submitted = true, task = task))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (sessions.current?.epoch == epoch && state.value.request?.key == request.key) {
                    val reason = (error as? GenerationException)?.reason ?: GenerationFailure.UNKNOWN
                    publish(request.copy(submitted = true, failure = reason))
                    mutable.update { it.copy(error = reason, purchaseNeeded = reason == GenerationFailure.INSUFFICIENT) }
                }
            } finally { if (sessions.current?.epoch == epoch && state.value.request?.key == request.key) mutable.update { it.copy(submitting = false) } }
        }
    }
    fun resume(request: GenerationRequest) {
        if (request.userId != sessions.current?.userId || state.value.submitting) return
        saved["generation_source"] = request.source
        publish(request)
        mutable.update { it.copy(error = null, purchaseNeeded = false) }
    }
    fun purchaseHandled() { mutable.update { it.copy(purchaseNeeded = false) } }
    fun photoPath() = mutable.value.request?.photo?.let { storage.photoFile(it).path }
    fun restart() { val template = state.value.template; val source = saved.get<String>("generation_source") ?: "unknown"; discard(); if (template != null) open(template, source) }
    fun discard() {
        selectionVersion++
        preparationJob?.cancel()
        val draft = mutable.value.request
        if (draft != null && !draft.submitted) viewModelScope.launch { storage.discard(draft.photo) }
        mutable.value = GenerationUiState(quality = options.defaultQuality)
        saved["generation_draft"] = null; saved["generation_template"] = null
    }
    private fun publish(draft: GenerationRequest) {
        saved["generation_draft"] = json.encodeToString(GenerationRequest.serializer(), draft)
        mutable.update { it.copy(request = draft, template = draft.template, quality = draft.quality) }
    }
}
