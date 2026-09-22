package yumo.achat.app.ui.imagevideo

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import yumo.achat.app.R
import yumo.achat.core.backend.AchatRepository
import yumo.achat.core.backend.ProfileEditingGateway
import yumo.achat.core.backend.UserProfile

internal enum class ProfileEditOperation { Name, Avatar }

internal class ProfileEditingController(
    private val gateway: ProfileEditingGateway,
    private val scope: CoroutineScope,
    private val fallbackError: (Throwable) -> String,
) {
    var nameSaving by mutableStateOf(false)
        private set
    var avatarSaving by mutableStateOf(false)
        private set
    var nameError by mutableStateOf<String?>(null)
        private set
    var updatedProfile by mutableStateOf<UserProfile?>(null)
        private set
    var completedOperation by mutableStateOf<ProfileEditOperation?>(null)
        private set
    var completionError by mutableStateOf<String?>(null)
        private set
    var completionSerial by mutableIntStateOf(0)
        private set

    fun clearNameError() {
        nameError = null
    }

    fun acknowledgeCompletion() {
        completedOperation = null
        completionError = null
    }

    fun canStartAvatarSave(): Boolean = !avatarSaving && !nameSaving

    private fun canStartNameSave(): Boolean = !nameSaving && !avatarSaving

    fun saveName(name: String) {
        if (!canStartNameSave()) return
        nameSaving = true
        nameError = null
        scope.launch {
            try {
                publishSuccess(ProfileEditOperation.Name, gateway.updateProfileName(name))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = fallbackError(error)
                nameError = message
                publishFailure(ProfileEditOperation.Name, message)
            } finally {
                nameSaving = false
            }
        }
    }

    fun saveAvatar(uri: Uri) {
        if (!canStartAvatarSave()) return
        avatarSaving = true
        scope.launch {
            try {
                publishSuccess(ProfileEditOperation.Avatar, gateway.updateProfileAvatar(uri))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                publishFailure(ProfileEditOperation.Avatar, fallbackError(error))
            } finally {
                avatarSaving = false
            }
        }
    }

    private fun publishSuccess(operation: ProfileEditOperation, profile: UserProfile) {
        updatedProfile = profile
        completedOperation = operation
        completionError = null
        completionSerial += 1
    }

    private fun publishFailure(operation: ProfileEditOperation, message: String) {
        completedOperation = operation
        completionError = message
        completionSerial += 1
    }
}

internal class ProfileEditingViewModel(application: Application) : AndroidViewModel(application) {
    val controller = ProfileEditingController(
        gateway = AchatRepository(application),
        scope = viewModelScope,
        fallbackError = { error ->
            apiEnvelopeUserMessage(
                error.message,
                application.getString(R.string.profile_update_failed),
            )
        },
    )
}
