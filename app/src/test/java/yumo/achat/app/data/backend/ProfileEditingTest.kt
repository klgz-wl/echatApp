package yumo.achat.app.data.backend

import org.json.JSONObject
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileEditingTest {
    @Test
    fun `nickname is trimmed and validated against backend limits`() {
        assertEquals("Nova Quinn", validatedProfileNickname("  Nova Quinn  "))
        assertThrows(IllegalArgumentException::class.java) { validatedProfileNickname("A") }
        assertThrows(IllegalArgumentException::class.java) { validatedProfileNickname("x".repeat(51)) }
    }

    @Test
    fun `profile update body only sends requested fields`() {
        val nicknameBody = JSONObject(profileUpdateBody(nickname = "Nova Quinn"))
        assertEquals("Nova Quinn", nicknameBody.getString("nickname"))
        assertNull(nicknameBody.optString("avatar").takeIf { it.isNotEmpty() })

        val avatarBody = JSONObject(profileUpdateBody(avatarUrl = "https://cdn.example/avatar.webp"))
        assertEquals("https://cdn.example/avatar.webp", avatarBody.getString("avatar"))
        assertEquals("https://cdn.example/avatar.webp", avatarBody.getString("avatar_large"))
    }

    @Test
    fun `avatar upload uses client file contract`() {
        val parts = profileAvatarUploadParts(
            fileName = "portrait.webp",
            contentType = "image/webp",
            bytes = byteArrayOf(1, 2, 3),
        )

        assertEquals(listOf("file", "upload_source"), parts.map { it.fieldName })
        assertEquals("profile", (parts[1] as MultipartFormData.TextPart).value)
    }

    @Test
    fun `avatar MIME falls back safely and rejects non images`() {
        assertEquals("image/jpeg", validatedProfileAvatarContentType(null))
        assertEquals("image/png", validatedProfileAvatarContentType("image/png"))
        assertThrows(IllegalArgumentException::class.java) {
            validatedProfileAvatarContentType("application/pdf")
        }
    }

    @Test
    fun `avatar upload rejects files larger than twenty megabytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            profileAvatarUploadParts(
                fileName = "too-large.jpg",
                contentType = "image/jpeg",
                bytes = ByteArray(20 * 1024 * 1024 + 1),
            )
        }
    }

    @Test
    fun `avatar reader stops when stream crosses size limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            readProfileAvatarBytes(SizedInputStream(MAX_PROFILE_AVATAR_BYTES + 1))
        }
    }

    private class SizedInputStream(private var remaining: Int) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length, remaining)
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
