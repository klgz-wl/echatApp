package yumo.achat.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class AchatBackendParsersTest {
    @Test
    fun `parse auth session from api envelope`() {
        val session = AchatBackendParsers.parseAuthSession(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "user_id": "user-1",
                "token": "access-token",
                "refresh_token": "refresh-token",
                "session_id": "session-1",
                "is_anonymous": true
              }
            }
            """.trimIndent(),
        )

        assertEquals("user-1", session.userId)
        assertEquals("access-token", session.token)
        assertEquals("refresh-token", session.refreshToken)
        assertEquals("session-1", session.sessionId)
        assertEquals(true, session.isAnonymous)
    }

    @Test
    fun `parse templates sorted by hot score`() {
        val templates = AchatBackendParsers.parseTemplates(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "items": [
                  {
                    "id": "cold",
                    "name": "Cold",
                    "file_url": "https://example.test/cold.webp",
                    "mime_type": "image/webp",
                    "width": 720,
                    "height": 1280,
                    "duration": 4,
                    "hot_score": 12,
                    "prices": { "fast": 11, "quality": 22 }
                  },
                  {
                    "id": "hot",
                    "name": "Hot",
                    "file_url": "https://example.test/hot.webp",
                    "mime_type": "video/mp4",
                    "width": 720,
                    "height": 1280,
                    "duration": 5,
                    "hot_score": 99,
                    "prices": { "fast": 9 }
                  }
                ],
                "total": 2,
                "page": 1,
                "page_size": 20
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("hot", "cold"), templates.map { it.id })
        assertEquals("Hot", templates.first().name)
        assertEquals("video/mp4", templates.first().mimeType)
        assertEquals(9, templates.first().fastPrice)
        assertEquals(null, templates.first().qualityPrice)
    }

    @Test
    fun `parse user summary with display fallback`() {
        val profile = AchatBackendParsers.parseUserProfile(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "id": "3095609813",
                "username": "quiet-user",
                "nickname": "Quiet wanderer",
                "avatar": "https://example.test/avatar.webp"
              }
            }
            """.trimIndent(),
        )
        val currency = AchatBackendParsers.parseUserCurrency(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "user_id": "3095609813",
                "diamond_balance": 42,
                "diamond_earned": 100,
                "diamond_spent": 58
              }
            }
            """.trimIndent(),
        )

        assertEquals("Quiet wanderer", profile.displayName)
        assertEquals("3095609813", profile.id)
        assertEquals(42, currency.diamondBalance)
    }

    @Test
    fun `parse uploaded visual resource`() {
        val resource = AchatBackendParsers.parseVisualResource(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "id": "resource-1",
                "task_id": "",
                "resource_type": "upload",
                "modality": "image",
                "url": "https://example.test/upload.webp",
                "thumbnail_url": "https://example.test/thumb.webp",
                "mime_type": "image/webp",
                "width": 720,
                "height": 1280,
                "duration": 0
              }
            }
            """.trimIndent(),
        )

        assertEquals("resource-1", resource.id)
        assertEquals("upload", resource.resourceType)
        assertEquals("image", resource.modality)
        assertEquals("https://example.test/upload.webp", resource.url)
        assertEquals("https://example.test/thumb.webp", resource.thumbnailUrl)
    }
}
