package yumo.achat.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives FCM data messages without starting analytics before the region gate. */
class AchatFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Notification display is handled by FCM when a notification payload is present.
        // Data-message routing waits for an explicit backend/product contract.
    }

    private companion object {
        const val PREFERENCES = "achat_firebase_messaging"
        const val KEY_TOKEN = "registration_token"
    }
}
