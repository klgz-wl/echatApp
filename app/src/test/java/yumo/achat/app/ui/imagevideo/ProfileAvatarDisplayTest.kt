package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileAvatarDisplayTest {
    @Test
    fun `local selected avatar previews before remote profile avatar`() {
        val local = "content://selected/avatar.jpg"

        val model = profileAvatarModel(
            localAvatarUri = local,
            remoteAvatarUrl = "https://cdn.example/old-avatar.webp",
            fallbackModel = "fallback",
        )

        assertEquals(local, model)
    }

    @Test
    fun `remote profile avatar is used before fallback when no local preview exists`() {
        val model = profileAvatarModel(
            localAvatarUri = null,
            remoteAvatarUrl = "https://cdn.example/avatar.webp",
            fallbackModel = "fallback",
        )

        assertEquals("https://cdn.example/avatar.webp", model)
    }

    @Test
    fun `blank remote profile avatar falls back to bundled avatar`() {
        val model = profileAvatarModel(
            localAvatarUri = null,
            remoteAvatarUrl = " ",
            fallbackModel = "fallback",
        )

        assertEquals("fallback", model)
    }
}
