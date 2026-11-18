package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class ShortDramaDiscoveryClientTest {
    @Test fun `catalog includes all rights classes and encodes a single tag`() = server { client, server ->
        server.createContext("/api/v1/short-dramas") { e ->
            assertEquals("Bearer token", e.requestHeaders.getFirst("Authorization"))
            val query=e.requestURI.rawQuery
            assertTrue(query.contains("is_home=true")); assertTrue(query.contains("is_vip=true"))
            assertTrue(query.contains("page=2")); assertTrue(query.contains("sort=latest")); assertTrue(query.contains("tag=Spicy"))
            respond(e,"""{"data":{"items":[{"drama_id":"d","title":"Drama","poster_url":"poster","tags":["Spicy"],"first_episode_id":"e","episode_count":2,"has_vip_episodes":true,"hot_score":12}],"page":2,"page_size":20,"total":21}}""")
        }
        val page=client.shortDramas("token",2,20,"latest","Spicy")
        assertEquals("e",page.items.single().firstEpisodeId); assertFalse(page.hasMore)
    }
    @Test fun `history preserves unavailable content and actual last episode`() = server { client, server ->
        server.createContext("/api/v1/short-dramas/history") { e ->
            assertEquals("Bearer token",e.requestHeaders.getFirst("Authorization"))
            assertFalse(e.requestURI.rawQuery.contains("is_vip"))
            respond(e,"""{"data":{"items":[{"drama_id":"d","title":"Drama","last_episode_id":"e3","episode_number":3,"available":false,"unavailable_reason":"CONTENT_UNAVAILABLE"}],"page":1,"page_size":20,"total":1}}""")
        }
        val entry=client.shortDramaHistory("token",1,20).items.single()
        assertEquals("e3",entry.lastEpisodeId); assertFalse(entry.available)
    }
    @Test fun `ads decode only real supported action without invented image`() = server { client, server ->
        server.createContext("/api/v1/short-drama-ads") { e -> respond(e,"""{"data":{"items":[{"ad_id":"ad","drama_id":"d","title":"Title","copy":"Copy","button_text":"Watch","button_action":"play"}]}}""") }
        assertEquals("play",client.shortDramaAds("token").single().action)
    }
    private fun respond(e:com.sun.net.httpserver.HttpExchange,body:String) { val bytes=body.toByteArray(); e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
    private fun server(block:(AchatBackendClient,HttpServer)->Unit) { val s=HttpServer.create(InetSocketAddress("127.0.0.1",0),0); s.start(); try { block(AchatBackendClient("http://127.0.0.1:${s.address.port}"),s) } finally { s.stop(0) } }
}
