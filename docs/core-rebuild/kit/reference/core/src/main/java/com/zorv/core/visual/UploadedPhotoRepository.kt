package com.zorv.core.visual

import com.zorv.core.auth.Session
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.catalog.TemplateMedia
import com.zorv.core.network.ApiResponse
import com.zorv.core.network.ServiceFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.ResponseBody
import retrofit2.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class UploadedPhoto(val id: String, val url: String,
    @SerialName("mime_type") val mimeType: String, val width: Int = 0, val height: Int = 0,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("resource_type") val resourceType: String = "upload") {
    fun media() = TemplateMedia(url, width, height, remote = true, mimeType = mimeType)
}
interface UploadedPhotoApi {
    @GET("visual-generation/resources?resource_type=upload&modality=image")
    suspend fun photos(@Query("page") page: Int, @Query("page_size") pageSize: Int, @Tag session: Session): ApiResponse<VisualPage<UploadedPhoto>>
}
/** 媒体下载使用独立客户端，不向 CDN 发送登录凭据、设备标识或用户头。 */
interface UploadedPhotoDownloadApi {
    @Streaming @GET suspend fun download(@Url url: String): ResponseBody
}
@Singleton
class UploadedPhotoRepository @Inject constructor(private val api: UploadedPhotoApi, private val sessions: SessionCoordinator,
    private val config: VisualPollingConfiguration) {
    suspend fun photos(page: Int): VisualPage<UploadedPhoto> {
        require(page > 0)
        val session = sessions.current ?: throw ServiceFailure.SignedOut
        val result = api.photos(page, config.pageSize, session).requireData()
        if (sessions.current?.epoch != session.epoch) throw ServiceFailure.Superseded
        if (result.page != page || result.pageSize !in 1..100 || result.total < 0 || result.items.any {
            it.id.isBlank() || it.resourceType != "upload" || !it.mimeType.startsWith("image/") ||
                it.width < 0 || it.height < 0 || it.url.isBlank() || !it.media().canLoad
        }) throw ServiceFailure.InvalidResponse
        return result
    }
}
