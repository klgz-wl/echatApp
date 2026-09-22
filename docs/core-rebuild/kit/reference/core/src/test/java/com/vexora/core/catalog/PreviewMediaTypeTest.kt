package com.vexora.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewMediaTypeTest {
    @Test fun `视频预览路径忽略签名查询参数和大小写`() {
        assertEquals("video/mp4", previewMediaType("https://cdn.test/preview.MP4?token=x.webp#frame", "https://cdn.test/main.mp4", "video/mp4"))
        assertEquals("video/webm", previewMediaType("https://cdn.test/preview.webm", "https://cdn.test/main.mp4", "video/mp4"))
    }
    @Test fun `视频模板的图片预览仍作为图片解码`() {
        for ((ext, type) in listOf("webp" to "image/webp", "gif" to "image/gif", "jpg" to "image/jpeg")) {
            assertEquals(type, previewMediaType("https://cdn.test/preview.$ext", "https://cdn.test/main.mp4", "video/mp4"))
        }
    }
    @Test fun `相同无后缀资源采用服务端类型但不猜测独立预览类型`() {
        assertEquals("video/mp4", previewMediaType("https://cdn.test/resource", "https://cdn.test/resource", "video/mp4"))
        assertEquals("", previewMediaType("https://cdn.test/preview", "https://cdn.test/resource", "video/mp4"))
    }
}
