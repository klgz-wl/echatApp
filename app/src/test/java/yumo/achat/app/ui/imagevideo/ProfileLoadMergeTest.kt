package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.core.backend.UserProfile

class ProfileLoadMergeTest {
    @Test
    fun `stale startup profile does not replace a locally saved profile`() {
        val current = AchatBackendUiState(
            profileName = "New name",
            profileId = "user-1",
            profileAvatarUrl = "https://cdn.example/new.webp",
        )
        val stale = UserProfile(
            id = "user-1",
            username = "old-user",
            nickname = "Old name",
            name = null,
            avatarUrl = "https://cdn.example/old.webp",
            largeAvatarUrl = null,
            isNew = true,
        )

        val merged = current.withLoadedProfile(stale, fallbackId = "user-1", preserveCurrent = true)

        assertEquals("New name", merged.profileName)
        assertEquals("https://cdn.example/new.webp", merged.profileAvatarUrl)
    }

    @Test
    fun `startup profile mode is retained even when visible profile is preserved`() {
        val current = AchatBackendUiState(profileName = "New name")
        val profile = UserProfile(
            id = "user-1",
            username = "old-user",
            nickname = "Old name",
            name = null,
            avatarUrl = null,
            largeAvatarUrl = null,
            isNew = true,
        )

        val merged = current.withLoadedProfile(profile, fallbackId = "user-1", preserveCurrent = true)

        assertEquals(true, merged.isNewProfile)
        assertEquals("New name", merged.profileName)
    }
}
