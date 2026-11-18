package yumo.achat.app.ui.imagevideo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.json.JSONObject
import yumo.achat.app.BuildConfig
import yumo.achat.core.backend.ShortDramaPlaybackGateway
import java.security.MessageDigest
import java.util.UUID

internal class PreferencesDramaUnlockStore(context: Context, userId: String) : DramaUnlockStore {
    private val preferences = context.applicationContext.getSharedPreferences("zorv_drama_unlock_intents", Context.MODE_PRIVATE)
    private val owner = digest("${BuildConfig.APPLICATION_ID}|${BuildConfig.ACHAT_API_BASE_URL}|$userId")
    private fun key(dramaId: String, episodeId: String) = "$owner:${digest("$dramaId|$episodeId") }"
    @Synchronized override fun pending(dramaId: String, episodeId: String, amount: Int): DramaUnlockIntent {
        require(amount >= 0)
        val storageKey = key(dramaId, episodeId)
        preferences.getString(storageKey, null)?.let { raw ->
            val previous = JSONObject(raw)
            check(previous.getString("drama") == dramaId && previous.getString("episode") == episodeId)
            if (previous.getInt("amount") == amount) {
                check(preferences.edit().putString(storageKey, raw).commit()) { "Unlock intent could not be saved" }
                return DramaUnlockIntent(dramaId, episodeId, amount, previous.getString("key"))
            }
        }
        val intent = DramaUnlockIntent(dramaId, episodeId, amount, UUID.randomUUID().toString())
        val value = JSONObject().put("drama", dramaId).put("episode", episodeId).put("amount", amount).put("key", intent.idempotencyKey)
        check(preferences.edit().putString(storageKey, value.toString()).commit()) { "Unlock intent could not be saved" }
        return intent
    }
    @Synchronized override fun clear(intent: DramaUnlockIntent) {
        val key = key(intent.dramaId, intent.episodeId)
        val raw = preferences.getString(key, null)
        if (raw == null) { check(preferences.edit().remove(key).commit()); return }
        if (JSONObject(raw).getString("key") == intent.idempotencyKey) check(preferences.edit().remove(key).commit())
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class ShortDramaPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private var owner: String? = null
    private var drama: String? = null
    private var consumedRequest: String? = null
    private var current: ShortDramaPlaybackController? = null
    private var unlockWatcher: Job? = null
    var walletRevision by mutableLongStateOf(0L)
        private set
    private val visibleSurfaces = mutableSetOf<String>()
    fun bind(ownerKey: String, userId: String, dramaId: String, gateway: ShortDramaPlaybackGateway): ShortDramaPlaybackController {
        if (owner != ownerKey || drama != dramaId || current == null) {
            release()
            owner = ownerKey; drama = dramaId
            current = ShortDramaPlaybackController(gateway, viewModelScope, PreferencesDramaUnlockStore(getApplication(), userId))
            current!!.setVisible(false)
            val bound = current!!
            unlockWatcher = viewModelScope.launch {
                snapshotFlow { bound.unlockSuccessSerial }.drop(1).collect { walletRevision++ }
            }
        }
        return current!!
    }
    fun openRequest(controller: ShortDramaPlaybackController, requestId: String, dramaId: String, episodeId: String?) {
        if (controller !== current || consumedRequest == requestId) return
        consumedRequest=requestId
        controller.start(dramaId,episodeId)
    }
    fun surface(controller: ShortDramaPlaybackController, token: String, visible: Boolean) {
        if (controller !== current) return
        if (visible) visibleSurfaces += token else visibleSurfaces -= token
        controller.setVisible(visibleSurfaces.isNotEmpty())
    }
    fun releaseOwner(ownerKey: String) { if (owner == ownerKey) release() }
    fun release() { unlockWatcher?.cancel(); unlockWatcher = null; current?.close(); current = null; visibleSurfaces.clear(); consumedRequest=null; owner = null; drama = null }
    override fun onCleared() { release() }
}
