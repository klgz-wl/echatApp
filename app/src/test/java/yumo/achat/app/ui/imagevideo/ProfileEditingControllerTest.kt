package yumo.achat.app.ui.imagevideo

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.backend.ProfileEditingGateway
import yumo.achat.core.backend.UserProfile

class ProfileEditingControllerTest {
    @Test
    fun `successful name save publishes returned server profile`() {
        val expected = profile("Nova Quinn")
        val controller = ProfileEditingController(
            gateway = FakeProfileGateway(nameResult = Result.success(expected)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fallbackError = { "fallback" },
        )

        controller.saveName("Nova Quinn")

        assertFalse(controller.nameSaving)
        assertNull(controller.nameError)
        assertEquals(expected, controller.updatedProfile)
        assertEquals(ProfileEditOperation.Name, controller.completedOperation)

        controller.acknowledgeCompletion()

        assertNull(controller.completedOperation)
        assertNull(controller.completionError)
    }

    @Test
    fun `failed name save keeps editor retryable`() {
        val controller = ProfileEditingController(
            gateway = FakeProfileGateway(nameResult = Result.failure(IllegalStateException("Name rejected"))),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fallbackError = { it.message ?: "fallback" },
        )

        controller.saveName("Nova Quinn")

        assertFalse(controller.nameSaving)
        assertEquals("Name rejected", controller.nameError)
        assertNull(controller.updatedProfile)
    }

    @Test
    fun `avatar save cannot start while name save is active`() {
        var avatarCalls = 0
        val gateway = object : ProfileEditingGateway {
            override suspend fun updateProfileName(name: String): UserProfile =
                suspendCancellableCoroutine { }

            override suspend fun updateProfileAvatar(uri: Uri): UserProfile {
                avatarCalls += 1
                return profile("Avatar")
            }
        }
        val controller = ProfileEditingController(
            gateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            fallbackError = { "fallback" },
        )

        controller.saveName("Nova Quinn")
        assertFalse(controller.canStartAvatarSave())
        assertEquals(0, avatarCalls)
        assertFalse(controller.avatarSaving)
    }

    private fun profile(name: String) = UserProfile(
        id = "user-1",
        username = "anonymous",
        nickname = name,
        name = null,
        avatarUrl = null,
        largeAvatarUrl = null,
    )

    private class FakeProfileGateway(
        private val nameResult: Result<UserProfile>,
    ) : ProfileEditingGateway {
        override suspend fun updateProfileName(name: String): UserProfile = nameResult.getOrThrow()

        override suspend fun updateProfileAvatar(uri: Uri): UserProfile = error("Not used")
    }
}
