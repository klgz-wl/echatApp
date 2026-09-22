package com.vexora.core.visual

import com.vexora.core.auth.Session
import com.vexora.core.network.ApiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

@Serializable data class VisualCategory(val id: String, val name: String, @SerialName("sort_order") val sortOrder: Int = 0)
@Serializable data class VisualPage<T>(val items: List<T>, val page: Int, @SerialName("page_size") val pageSize: Int, val total: Long)
@Serializable data class VisualTemplate(val id: String, val name: String,
    @SerialName("category_id") val categoryId: String? = null, @SerialName("category_name") val categoryName: String = "",
    @SerialName("file_url") val fileUrl: String, @SerialName("mime_type") val mimeType: String,
    val width: Int, val height: Int, val duration: Double = 0.0,
    @SerialName("hot_score") val hotScore: Int = 0, @SerialName("created_at") val createdAt: String = "",
    val prices: com.vexora.core.catalog.TemplatePrices? = null,
    @SerialName("preview_url") val previewUrl: String? = null, val tags: List<String> = emptyList(),
    @SerialName("is_home_featured") val isHomeFeatured: Boolean = false)
@Serializable data class VisualResult(val id: String, val url: String, @SerialName("mime_type") val mimeType: String,
    val width: Int, val height: Int, val duration: Double = 0.0)
@Serializable data class VisualTask(@SerialName("task_id") val taskId: String, val status: String, val modality: String,
    val quality: String, @SerialName("template_id") val templateId: String,
    @SerialName("diamond_cost") val diamondCost: Long,
    @SerialName("estimated_poll_interval_seconds") val pollIntervalSeconds: Int? = null,
    val refunded: Boolean = false, @SerialName("refund_amount") val refundAmount: Long? = null,
    @SerialName("error_code") val errorCode: String? = null, @SerialName("error_message") val errorMessage: String? = null,
    val resource: VisualResult? = null, @SerialName("created_at") val createdAt: String,
    @SerialName("started_at") val startedAt: String? = null, @SerialName("completed_at") val completedAt: String? = null)
@Serializable data class VisualResource(val id: String, @SerialName("task_id") val taskId: String, val modality: String,
    @SerialName("template_id") val templateId: String, @SerialName("template_name") val templateName: String,
    val url: String, @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("mime_type") val mimeType: String, val width: Int, val height: Int, val duration: Double = 0.0,
    @SerialName("created_at") val createdAt: String)

/** modality 只能由客户端枚举选择 image 或 video，不接受外部 URL 作为路径。 */
interface VisualGenerationApi {
    @GET("visual-generation/{modality}/categories") suspend fun categories(@Path("modality") modality: String, @Tag session: Session): ApiResponse<List<VisualCategory>>
    @GET("visual-generation/{modality}/templates") suspend fun templates(@Path("modality") modality: String,
        @Query("page") page: Int, @Query("page_size") pageSize: Int, @Query("category_id") categoryId: String?,
        @Query("home_featured") homeFeatured: Boolean?, @Tag session: Session): ApiResponse<VisualPage<VisualTemplate>>
    @Multipart @POST("visual-generation/{modality}/tasks") suspend fun createTask(@Path("modality") modality: String,
        @Header("Idempotency-Key") idempotencyKey: String, @Part("template_id") templateId: RequestBody,
        @Part("quality") quality: RequestBody, @Part image: MultipartBody.Part?, @Tag session: Session): ApiResponse<VisualTask>
    @GET("visual-generation/tasks/{id}") suspend fun task(@Path("id") id: String, @Tag session: Session): ApiResponse<VisualTask>
    // 新版接口默认包含上传资源，作品页明确只读取生成结果。
    @GET("visual-generation/resources?resource_type=generated") suspend fun resources(@Query("page") page: Int,
        @Query("page_size") pageSize: Int, @Query("modality") modality: String?, @Tag session: Session): ApiResponse<VisualPage<VisualResource>>
}
