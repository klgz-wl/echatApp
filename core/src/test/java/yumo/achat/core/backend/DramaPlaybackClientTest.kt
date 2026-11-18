package yumo.achat.core.backend

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DramaPlaybackClientTest {
    private val grant = """{"data":{"authorization_id":"authorization","grant":"new-grant","media_resolver":"/api/v1/short-dramas/media/new-grant","expires_at":"soon","subtitles":[{"track_id":"track","language":"en","display_name":"English","format":"vtt","resolver_url":"/api/v1/short-dramas/media/new-grant/subtitles/track"}]}}"""
    @Test fun `play sends once and refresh never replays`() = server { client, http ->
        var plays = 0; var refreshes = 0
        http.createContext("/api/v1/short-dramas/d/episodes/e/play") { e ->
            plays++; assertEquals("POST",e.requestMethod); assertEquals("{}",e.requestBody.bufferedReader().readText())
            respond(e,grant)
        }
        http.createContext("/api/v1/short-dramas/media/old-grant/refresh") { e ->
            refreshes++; assertEquals("POST",e.requestMethod); assertEquals("{}",e.requestBody.bufferedReader().readText()); respond(e,grant)
        }
        assertEquals("English",client.playDramaEpisode("token","d","e").subtitles.single().displayName)
        assertEquals("new-grant",client.refreshDramaGrant("token","old-grant").grant)
        assertEquals(1,plays); assertEquals(1,refreshes)
    }
    @Test fun `failed play is not automatically retried`() = server { client, http ->
        var calls=0
        http.createContext("/api/v1/short-dramas/d/episodes/e/play") { e -> calls++; e.responseHeaders.add("Retry-After","0"); respond(e,"{}",503) }
        assertThrows(HomeFeedServiceException::class.java) { client.playDramaEpisode("token","d","e") }
        assertEquals(1,calls)
    }
    @Test fun `unlock sends confirmed coins and stable payment identity`() = server { client, http ->
        http.createContext("/api/v1/short-dramas/d/episodes/e/unlock") { e ->
            val body=JSONObject(e.requestBody.bufferedReader().readText())
            assertEquals("purchase-key",body.getString("payment_idempotency_key")); assertEquals(5,body.getInt("confirmed_amount")); assertEquals("coins",body.getString("currency"))
            respond(e,"""{"data":{"is_unlocked":true}}""")
        }
        client.unlockDramaEpisode("token","d","e",5,"purchase-key")
    }
    @Test fun `formal media and subtitle resolvers reject preview grants redirects and external origins`() = server { client, http ->
        http.createContext("/api/v1/short-dramas/media/full") { e -> respond(e,"""{"data":{"url":"https://cdn.example/video","expires_at":"soon","grant_type":"preview"}}""") }
        http.createContext("/api/v1/short-dramas/media/redirect") { e -> e.responseHeaders.add("Location","https://evil.example"); e.sendResponseHeaders(302,-1); e.close() }
        assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaMedia("token","/api/v1/short-dramas/media/full") }
        assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaMedia("token","/api/v1/short-dramas/media/redirect") }
        assertThrows(HomeFeedServiceException::class.java) { client.resolveDramaSubtitle("token","https://evil.example/api/v1/short-dramas/media/full/subtitles/track") }
        http.createContext("/api/v1/short-dramas/media/g/subtitles/t") { e -> respond(e,"""{"data":{"url":"https://cdn.example/sub.vtt","expires_at":"soon","grant_type":"full"}}""") }
        assertEquals("https://cdn.example/sub.vtt",client.resolveDramaSubtitle("token","/api/v1/short-dramas/media/g/subtitles/t").url)
    }
    @Test fun `descriptions are optional and real values survive parsing`() {
        val base="""{"drama_id":"d","title":"Title","episodes":[]}"""
        assertEquals("",HomeFeedParsers.drama(JSONObject(base)).description)
        assertEquals("Actual",HomeFeedParsers.drama(JSONObject(base).put("description","Actual")).description)
        val batch=JSONObject("""{"batch_id":"b","offset":0,"items":[{"id":"i","resource_id":"d","kind":"short_drama","description":"Real"}]}""")
        assertEquals("Real",HomeFeedParsers.batch(batch).items.single().description)
    }
    private fun respond(e:com.sun.net.httpserver.HttpExchange,body:String,status:Int=200) { val bytes=body.toByteArray(); e.sendResponseHeaders(status,bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
    private fun server(block:(AchatBackendClient,HttpServer)->Unit) { val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0); http.start(); try { block(AchatBackendClient("http://127.0.0.1:${http.address.port}"),http) } finally { http.stop(0) } }
}
