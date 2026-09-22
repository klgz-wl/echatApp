package com.vexora.core.catalog

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TemplateRepositoryTest {
    private val assets = File("../app/src/main/assets")
    private val json = Json { ignoreUnknownKeys = true }
    private val source = CatalogAssetSource { File(assets, it).readText() }
    @Test fun `三个入口从独立路径加载且图片无视频字段`() = runBlocking {
        val paths = json.decodeFromString<Map<String, String>>(File(assets, "config/catalog_sources.json").readText())
        assertEquals(3, paths.values.distinct().size)
        val repository = MockTemplateRepository(source, paths, json)
        val catalogs = CatalogChannel.entries.map { repository.load(it) }
        val ids = catalogs.flatMap { it.templates }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(catalogs.last().templates.all { it.mediaKind == MediaKind.IMAGE && it.durationSeconds == null && !it.hasVoice })
    }
    @Test fun `分类筛选不回退其他分类内容`() = runBlocking {
        val catalog = MockTemplateRepository(source, mapOf("VIDEO" to "mock/video.json"), json).load(CatalogChannel.VIDEO)
        assertTrue(catalog.filtered("new").isNotEmpty())
        assertTrue(catalog.filtered("new").all { "new" in it.categories })
        assertTrue(catalog.filtered("featured").isEmpty())
        assertTrue(catalog.filtered("missing").isEmpty())
    }
    @Test fun `模拟失败可重试且不污染其他入口`() = runBlocking {
        val repository = MockTemplateRepository(source, mapOf("HOME" to "mock/scenarios/catalog_retry.json", "IMAGE" to "mock/image.json"), json)
        try { repository.load(CatalogChannel.HOME); fail() } catch (_: java.io.IOException) { }
        assertTrue(repository.load(CatalogChannel.IMAGE).templates.isNotEmpty())
        assertTrue(repository.load(CatalogChannel.HOME).templates.isNotEmpty())
    }
    @Test fun `空数据保持空态不偷偷补充默认模板`() = runBlocking {
        val repository = MockTemplateRepository(source, mapOf("HOME" to "mock/scenarios/catalog_empty.json"), json)
        assertTrue(repository.load(CatalogChannel.HOME).templates.isEmpty())
    }
    @Test fun `重复模板和未知分类会拒绝加载`() {
        val template = Template("same", "title", "description", listOf("missing"), MediaKind.IMAGE, TemplateMedia("image", 1, 1))
        assertThrows(IllegalArgumentException::class.java) { TemplateCatalog(emptyList(), listOf(template)).validate() }
        assertThrows(IllegalArgumentException::class.java) { TemplateCatalog(listOf(TemplateCategory("missing", "label")), listOf(template, template)).validate() }
    }
}
