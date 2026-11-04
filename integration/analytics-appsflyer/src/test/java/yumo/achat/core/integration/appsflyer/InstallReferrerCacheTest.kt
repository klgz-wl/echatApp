package yumo.achat.core.integration.appsflyer

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InstallReferrerCacheTest {
    @Test fun `successful cache preserves null strings and primitive types in canonical structure`() {
        val raw = InstallReferrerRaw(null, 11, 22, 33, 44, null, false)
        val encoded = encodeInstallReferrerCache(123L, raw)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(123L), root["first_install_time_ms"])
        val data = root.getValue("data").jsonObject
        assertEquals(JsonNull, data["install_referrer"])
        assertEquals(JsonNull, data["install_version"])
        assertEquals(JsonPrimitive(false), data["google_play_instant"])
        assertEquals(JsonPrimitive(33L), data["referrer_click_timestamp_server_seconds"])
        assertEquals(raw, decodeInstallReferrerCache(encoded, 123L))
        assertNull(decodeInstallReferrerCache(encoded, 124L))
    }

    @Test fun `invalid install times and malformed types never hit`() {
        val encoded = encodeInstallReferrerCache(123L, InstallReferrerRaw("", 1, 2))
        assertNull(decodeInstallReferrerCache(encoded, 0))
        assertNull(decodeInstallReferrerCache(encoded, -1))
        assertTrue(runCatching { encodeInstallReferrerCache(0, InstallReferrerRaw("", 1, 2)) }.isFailure)
        assertNull(decodeInstallReferrerCache(encoded.replace("123", "\"123\""), 123L))
        assertNull(decodeInstallReferrerCache("{bad", 123L))
    }

    @Test fun `legacy successful camel case cache migrates only for matching positive installation`() {
        val legacy = """{"installReferrer":"","referrerClickTimestampSeconds":1,"installBeginTimestampSeconds":2}"""
        assertEquals(InstallReferrerRaw("", 1, 2), decodeInstallReferrerCache(legacy, 123L, 123L))
        assertNull(decodeInstallReferrerCache(legacy, 123L, 124L))
        assertNull(decodeInstallReferrerCache(legacy, 0L, 0L))
        val raw = decodeInstallReferrerCache(legacy, 123L, 123L)!!
        assertEquals(raw, decodeInstallReferrerCache(encodeInstallReferrerCache(123L, raw), 123L))
    }
}
