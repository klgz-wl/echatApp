package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class HomeFeedClientTest {
    @Test fun `feed uses request identity and parses nested template`() = server { client, http ->
        http.createContext("/api/v1/resource-catalog/home-feed") { e ->
            assertEquals("request_id=request-1&limit=20&is_vip=1", e.requestURI.rawQuery)
            assertEquals("Bearer test", e.requestHeaders.getFirst("Authorization"))
            val body = """{"data":{"batch_id":"batch","offset":5,"items":[{"id":"catalog","kind":"video_template","resource_id":"template","title":"Title","cover_url":"cover","vip":true,"prices":{"fast":9},"details":{"file_url":"video","duration":5.5}}]}}""".toByteArray()
            e.sendResponseHeaders(200,body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        val batch = client.homeFeed("test", "request-1")
        assertEquals(5,batch.offset); assertEquals("video",batch.items.single().fileUrl)
        assertEquals(9,batch.items.single().fastPrice)
    }
    @Test fun `business errors expose safe code`() = server { client, http ->
        http.createContext("/api/v1/resource-catalog/home-feed") { e ->
            val body = """{"code":"BATCH_INVALIDATED","message":"secret"}""".toByteArray()
            e.sendResponseHeaders(409,body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        val error = assertThrows(HomeFeedServiceException::class.java) { client.homeFeed("test", "request") }
        assertEquals("BATCH_INVALIDATED",error.code); assertEquals(409,error.statusCode)
        assertFalse(error.message.orEmpty().contains("secret"))
    }
    @Test fun `preview rejects external resolver`() = server { client, _ ->
        assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaPreview("test", "https://evil.example/api/v1/short-dramas/previews/grant") }
    }
    @Test fun `ack sends absolute index and idempotency identity`() = server { client, http ->
        var received = false
        http.createContext("/api/v1/resource-catalog/home-feed/batch/ack") { e ->
            assertEquals("POST", e.requestMethod)
            val body = org.json.JSONObject(e.requestBody.bufferedReader().readText())
            assertEquals(7, body.getInt("displayed_through_index"))
            assertEquals("ack-1", body.getString("idempotency_key"))
            received = true
            val bytes = """{"data":{"status":"accepted"}}""".toByteArray()
            e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        client.acknowledgeHomeFeed("test", "batch", 7, "ack-1")
        assertTrue(received)
    }
    @Test fun `resolver refuses redirects and never forwards authorization`() = server { client, http ->
        var redirected = false
        http.createContext("/api/v1/short-dramas/previews/grant") { e ->
            e.responseHeaders.add("Location", "/capture"); e.sendResponseHeaders(302,-1); e.close()
        }
        http.createContext("/capture") { e -> redirected = true; e.sendResponseHeaders(200,-1); e.close() }
        assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaPreview("test", "/api/v1/short-dramas/previews/grant") }
        assertFalse(redirected)
    }
    @Test fun `resolver and detail parse code free envelopes`() = server { client, http ->
        http.createContext("/api/v1/short-dramas/previews/grant") { e ->
            val bytes = """{"data":{"url":"https://cdn.example/signed","expires_at":"soon"}}""".toByteArray()
            e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        http.createContext("/api/v1/short-dramas/drama") { e ->
            val bytes = """{"data":{"drama_id":"drama","title":"Title","tags":["romance"],"episodes":[{"id":"episode","episode_number":1,"vip":true,"amount":null,"can_play":false,"access_reason":"VIP_REQUIRED"}]}}""".toByteArray()
            e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        assertEquals("https://cdn.example/signed",client.resolveDramaPreview("test", "/api/v1/short-dramas/previews/grant").url)
        val drama=client.shortDramaDetail("test", "drama")
        assertEquals(listOf("romance"),drama.tags); assertNull(drama.episodes.single().amount)
        assertTrue(drama.episodes.single().vip)
    }
    @Test fun `catalog context must be complete and valid`() {
        val valid = """{"batch_id":"b","offset":0,"items":[{"id":"c","kind":"video_template","resource_id":"t","catalog_context":{"catalog_id":"11111111-1111-4111-8111-111111111111","catalog_item_id":"22222222-2222-4222-8222-222222222222","catalog_binding_revision":4}}]}"""
        assertEquals(4L,HomeFeedParsers.batch(org.json.JSONObject(valid)).items.single().catalogContext!!.catalogBindingRevision)
        assertThrows(Exception::class.java) { HomeFeedParsers.batch(org.json.JSONObject(valid.replace("\"catalog_binding_revision\":4", "\"catalog_binding_revision\":0"))) }
        assertThrows(Exception::class.java) { HomeFeedParsers.batch(org.json.JSONObject(valid.replace(",\"catalog_binding_revision\":4", ""))) }
    }
    @Test fun `generation transmits catalog context as complete multipart group`() = server { client, http ->
        var body = ""
        http.createContext("/api/v1/visual-generation/video/tasks") { e ->
            body = e.requestBody.bufferedReader().readText()
            val bytes = """{"code":0,"data":{"task_id":"task-1","status":"queued"}}""".toByteArray()
            e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        val context = HomeCatalogContext("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", 4)
        client.createVisualGenerationTask("test", "video", "template", "fast", "resource", "key", context)
        assertTrue(body.contains("name=\"catalog_id\"")); assertTrue(body.contains(context.catalogId))
        assertTrue(body.contains("name=\"catalog_item_id\"")); assertTrue(body.contains(context.catalogItemId))
        assertTrue(body.contains("name=\"catalog_binding_revision\"\r\n\r\n4"))
        client.createVisualGenerationTask("test", "video", "template", "fast", "resource", "key-2")
        assertFalse(body.contains("catalog_id")); assertFalse(body.contains("catalog_binding_revision"))
    }
    @Test fun `ack rejects absent or unaccepted confirmation`() {
        listOf("{}", "{\"status\":\"pending\"}").forEach { data -> server { client, http ->
            http.createContext("/api/v1/resource-catalog/home-feed/b/ack") { e ->
                e.requestBody.close()
                val bytes = "{\"data\":$data}".toByteArray()
                e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            assertThrows(HomeFeedServiceException::class.java) { client.acknowledgeHomeFeed("test", "b", 0, "ack") }
        } }
    }
    @Test fun `preview rejects invalid media URLs and full grants`() {
        listOf("file:///tmp/video" to "preview", "https://user:pass@cdn.example/video" to "preview", "https:///video" to "preview", "https://cdn.example/video" to "full").forEach { (url, grant) -> server { client, http ->
            http.createContext("/api/v1/short-dramas/previews/grant") { e ->
                val bytes = org.json.JSONObject().put("data",org.json.JSONObject().put("url",url).put("expires_at","soon").put("grant_type",grant)).toString().toByteArray()
                e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
            }
            assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaPreview("test", "/api/v1/short-dramas/previews/grant") }
        } }
    }
    @Test fun `episode identity prefers episode id and supports missing legacy id`() {
        listOf("{\"episode_id\":\"right\",\"id\":\"wrong\",\"episode_number\":1}", "{\"episode_id\":\"right\",\"episode_number\":1}").forEach { episode ->
            val json = org.json.JSONObject("{\"drama_id\":\"d\",\"title\":\"D\",\"episodes\":[$episode]}")
            assertEquals("right",HomeFeedParsers.drama(json).episodes.single().episodeId)
        }
    }
    @Test fun `feed bounds and prices reject invalid integers`() {
        listOf("{\"batch_id\":\"\",\"offset\":0,\"items\":[]}", "{\"batch_id\":\"b\",\"offset\":-1,\"items\":[]}").forEach { json ->
            assertThrows(Exception::class.java) { HomeFeedParsers.batch(org.json.JSONObject(json)) }
        }
        listOf("-1", "1.5", "2147483648").forEach { amount ->
            assertThrows(Exception::class.java) { HomeFeedParsers.drama(org.json.JSONObject("{\"drama_id\":\"d\",\"title\":\"D\",\"episodes\":[{\"id\":\"e\",\"episode_number\":1,\"amount\":$amount}]}")) }
        }
    }
    private fun server(block:(AchatBackendClient,HttpServer)->Unit) {
        val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0); http.start()
        try { block(AchatBackendClient("http://127.0.0.1:${http.address.port}"),http) } finally { http.stop(0) }
    }
}
