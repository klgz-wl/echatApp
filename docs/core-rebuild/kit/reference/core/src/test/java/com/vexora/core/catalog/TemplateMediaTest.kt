package com.vexora.core.catalog

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TemplateMediaTest {
    @Test fun `未知尺寸远端作品可用于缩略图和预览导航且不伪造比例`() {
        for (mime in listOf("image/png", "video/mp4")) {
            val media = TemplateMedia("https://example.test/result", 0, 0, true, mime)
            assertTrue(media.canLoad)
            assertFalse(media.hasDimensions)
            assertEquals(media, Json.decodeFromString<TemplateMedia>(Json.encodeToString(media)))
            assertTrue(runCatching { media.aspectRatio }.exceptionOrNull() is IllegalStateException)
        }
        val known = TemplateMedia("https://example.test/result", 1920, 1080, true)
        assertTrue(known.hasDimensions); assertEquals(1920f / 1080f, known.aspectRatio)
    }
    @Test fun `本地素材仍需尺寸且远端不能使用负尺寸或本机URL`() {
        assertTrue(runCatching { TemplateMedia("local", 0, 0) }.isFailure)
        assertTrue(runCatching { TemplateMedia("https://example.test/result", -1, 0, true) }.isFailure)
        assertFalse(TemplateMedia("http://localhost/result", 0, 0, true).canLoad)
    }
}
