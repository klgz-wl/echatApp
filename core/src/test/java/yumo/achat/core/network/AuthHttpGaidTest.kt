package yumo.achat.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import yumo.achat.core.attribution.attributionPayload
import yumo.achat.core.config.ClientIdentity

class AuthHttpGaidTest {
    @Test fun `Retrofit serializes the same GAID in both login locations for every login route`() = runBlocking {
        val bodies = mutableListOf<Pair<String, JsonObject>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            bodies += chain.request().url.encodedPath to Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"token":"token","user_id":"user"}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/api/v1/").client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
            .create(PublicAuthApi::class.java)
        val id = "12345678-1234-4234-8234-123456789abc"
        val attribution = attributionPayload(JsonObject(emptyMap()), ClientIdentity("test.app", "1", 30), id, "af").copy(gaid = id)
        api.login(LoginRequest("user", "password", "install", "test.app", "android", "1", attribution, id))
        api.anonymous(AnonymousRequest("install", "test.app", "android", "1", attribution, id))
        api.google(GoogleRequest("google-token", "install", "test.app", "android", "1", attribution, id))
        assertEquals(listOf("/api/v1/auth/login", "/api/v1/auth/anonymous", "/api/v1/auth/google"), bodies.map { it.first })
        bodies.forEach { (_, body) ->
            assertEquals(id, body.getValue("gaid").jsonPrimitive.content)
            assertEquals(id, body.getValue("attribution").jsonObject.getValue("gaid").jsonPrimitive.content)
            assertEquals("install", body.getValue("device_id").jsonPrimitive.content)
        }
        api.anonymous(AnonymousRequest("install", "test.app", "android", "1", attribution.copy(gaid = null)))
        val unavailable = bodies.last().second
        assertFalse(unavailable.containsKey("gaid"))
        assertFalse(unavailable.getValue("attribution").jsonObject.containsKey("gaid"))
    }
}
