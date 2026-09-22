package yumo.achat.core.visual

import yumo.achat.core.auth.SessionCoordinator
import yumo.achat.core.analytics.*
import yumo.achat.core.catalog.Template
import yumo.achat.core.network.ServiceFailure
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import retrofit2.HttpException

@Serializable data class SourcePhoto(val fileName: String, val mimeType: String, val width: Int, val height: Int, val size: Long)
@Serializable data class GenerationRequest(val key: String = UUID.randomUUID().toString(), val userId: String,
    val template: Template, val quality: String, val photo: SourcePhoto, val submitted: Boolean = false,
    val task: VisualTask? = null, val failure: GenerationFailure? = null,
    val homeNoticeDismissed: Boolean = false, val source: String = "unknown")
@Serializable enum class GenerationFailure { INSUFFICIENT, CONFLICT, INVALID_IMAGE, UNAVAILABLE, UNKNOWN, SIGNED_OUT }
class GenerationException(val reason: GenerationFailure) : Exception()
interface GenerationStorage {
    suspend fun read(userId: String): List<GenerationRequest>
    suspend fun write(userId: String, requests: List<GenerationRequest>)
    fun photoFile(photo: SourcePhoto): File
    suspend fun clear(userId: String) { write(userId, emptyList()) }
}

/** 提交前先持久写入同一幂等键；任务接受前的超时保留请求，重试不会变成第二次购买。 */
@Singleton
class GenerationRequestRepository @Inject constructor(private val api: VisualGenerationApi,
    private val sessions: SessionCoordinator, private val storage: GenerationStorage, private val json: Json, private val events: EventTracker = NoOpEventTracker) {
    private val mutex = Mutex()
    suspend fun pending(userId: String) = mutex.withLock { storage.read(userId).also { it.forEach(::reportResult) } }
    /** 只确认首页终态通知，不删除任务；后续退款查询仍使用同一记录。重复点击不重复导航。 */
    suspend fun acknowledgeHomeNotice(request: GenerationRequest, epoch: String): GenerationRequest? = mutex.withLock {
        if (sessions.current?.epoch != epoch || sessions.current?.userId != request.userId) throw ServiceFailure.Superseded
        val records = storage.read(request.userId)
        val known = records.firstOrNull { it.key == request.key } ?: return@withLock null
        if (known.homeNoticeDismissed || known.task?.status !in setOf("processing", "succeeded", "failed")) return@withLock null
        if (sessions.current?.epoch != epoch) throw ServiceFailure.Superseded
        val acknowledged = if (known.task?.status in setOf("succeeded", "failed")) {
            known.copy(homeNoticeDismissed = true).also { updated ->
                // 保持顺序，避免已查看操作改变其它任务的浮层优先级。
                storage.write(request.userId, records.map { if (it.key == known.key) updated else it })
            }
        } else known
        if (sessions.current?.epoch != epoch) throw ServiceFailure.Superseded
        acknowledged
    }
    suspend fun submit(request: GenerationRequest): VisualTask = mutex.withLock {
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        if (session.userId != request.userId) throw ServiceFailure.Superseded
        require(request.quality in setOf("fast", "quality"))
        val known = storage.read(session.userId).firstOrNull { it.key == request.key }
        if (known != null && (known.template.id != request.template.id || known.template.mediaKind != request.template.mediaKind ||
                known.quality != request.quality || known.photo != request.photo)) throw GenerationException(GenerationFailure.CONFLICT)
        known?.task?.let { return@withLock it }
        if (request.template.prices?.forQuality(request.quality) == null) throw GenerationException(GenerationFailure.UNAVAILABLE)
        val file = storage.photoFile(request.photo)
        if (!file.isFile || file.length() != request.photo.size) throw GenerationException(GenerationFailure.INVALID_IMAGE)
        val saved = request.copy(submitted = true, failure = null)
        persist(saved)
        try {
            if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
            val response = api.createTask(request.template.mediaKind.name.lowercase(java.util.Locale.ROOT), request.key,
                request.template.id.toRequestBody("text/plain".toMediaType()), request.quality.toRequestBody("text/plain".toMediaType()),
                MultipartBody.Part.createFormData("image", request.photo.fileName, file.asRequestBody(request.photo.mimeType.toMediaType())), session)
            if (response.code != 0) throw GenerationException(failureFor(response.code))
            val task = response.requireData()
            if (task.taskId.isBlank() || task.templateId != request.template.id || task.quality != request.quality ||
                task.modality != request.template.mediaKind.name.lowercase(java.util.Locale.ROOT) || task.diamondCost < 0 ||
                task.status !in setOf("processing", "succeeded", "failed")) throw ServiceFailure.InvalidResponse
            // 已收到的受理结果即使页面取消也保存；不把它发布到别的账号。
            withContext(NonCancellable) { if (sessions.current?.epoch == session.epoch) persist(saved.copy(task = task)) }
            if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
            events.track("upload_img_result", request.template.analyticsProperties() + mapOf("phase" to "task_submit", "request_id" to request.key,
                "is_success" to true, "task_id" to task.taskId), session.userId)
            reportResult(saved.copy(task = task))
            task
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val reason = when (error) {
                is GenerationException -> error.reason
                is HttpException -> {
                    val code = runCatching { json.parseToJsonElement(error.response()?.errorBody()?.string().orEmpty()).jsonObject["code"]?.jsonPrimitive?.intOrNull }.getOrNull()
                    code?.let(::failureFor)?.takeUnless { it == GenerationFailure.UNKNOWN } ?: failureFor(error.code())
                }
                is ServiceFailure.SignedOut, is ServiceFailure.Superseded -> GenerationFailure.SIGNED_OUT
                else -> GenerationFailure.UNKNOWN
            }
            withContext(NonCancellable) { if (sessions.current?.epoch == session.epoch) persist(saved.copy(failure = reason)) }
            events.track("upload_img_result", request.template.analyticsProperties() + mapOf("phase" to "task_submit", "request_id" to request.key,
                "is_success" to false, "fail_reason" to reason.name.lowercase(java.util.Locale.ROOT)), session.userId)
            throw GenerationException(reason)
        }
    }
    suspend fun refresh(request: GenerationRequest): VisualTask = mutex.withLock {
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        if (session.userId != request.userId) throw ServiceFailure.Superseded
        val known = storage.read(session.userId).firstOrNull { it.key == request.key } ?: throw ServiceFailure.InvalidResponse
        val previous = known.task ?: throw ServiceFailure.InvalidResponse
        val response = api.task(previous.taskId, session).requireData()
        val next = reconcile(previous, response)
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        withContext(NonCancellable) { if (sessions.current?.epoch == session.epoch) persist(known.copy(task = next)) }
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        reportResult(known.copy(task = next))
        next
    }
    suspend fun clear(userId: String) = mutex.withLock { storage.clear(userId) }
    private suspend fun persist(value: GenerationRequest) {
        val all = storage.read(value.userId)
        storage.write(value.userId, all.filterNot { it.key == value.key } + value)
    }
    private fun reportResult(request: GenerationRequest) {
        val task = request.task?.takeIf { it.status in setOf("succeeded", "failed") } ?: return
        val properties = request.template.analyticsProperties() + buildMap<String, Any> {
            put("request_id", request.key); put("task_id", task.taskId); put("quality", task.quality)
            put("diamond_cost", task.diamondCost); put("is_success", task.status == "succeeded"); put("source", request.source)
            if (task.status == "failed") put("fail_reason", "generation_failed")
            val start = yumo.achat.core.payment.paymentTime(task.startedAt ?: task.createdAt)
            val end = yumo.achat.core.payment.paymentTime(task.completedAt)
            if (start != null && end != null && end >= start) put("duration", end - start)
        }
        events.track("generate_result", properties, request.userId, "generation:${task.taskId}:terminal")
    }
    companion object {
        fun reconcile(previous: VisualTask, next: VisualTask): VisualTask {
            if (previous.taskId != next.taskId || previous.templateId != next.templateId || previous.modality != next.modality ||
                previous.quality != next.quality || previous.diamondCost != next.diamondCost || next.diamondCost < 0 ||
                next.status !in setOf("processing", "succeeded", "failed") || (next.refundAmount ?: 0) < 0 ||
                (next.refundAmount ?: 0) > next.diamondCost) throw ServiceFailure.InvalidResponse
            if (previous.status != "processing" && previous.status != next.status) return previous
            if (previous.refunded && !next.refunded) return previous
            return next
        }

        fun failureFor(code: Int) = when (code) {
            402, 400101 -> GenerationFailure.INSUFFICIENT
            409 -> GenerationFailure.CONFLICT
            400 -> GenerationFailure.INVALID_IMAGE
            401, 403 -> GenerationFailure.SIGNED_OUT
            503 -> GenerationFailure.UNAVAILABLE
            else -> GenerationFailure.UNKNOWN
        }
    }
}
