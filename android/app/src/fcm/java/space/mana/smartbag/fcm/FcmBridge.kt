package space.mana.smartbag.fcm

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import space.mana.smartbag.Notify
import space.mana.smartbag.PushHooks
import space.mana.smartbag.SmartBagApp

/** Compiled only when app/google-services.json exists (see app/build.gradle.kts). */
object FcmBridge {
    @JvmStatic fun install() {
        PushHooks.tokenFetcher = { cb ->
            try { FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> cb(if (t.isSuccessful) t.result else null) } }
            catch (_: Exception) { cb(null) }
        }
    }
}

/**
 * Server pushes: a trip charged on another phone of the account, a new sign-in, a card added elsewhere.
 * In the background Android shows the notification payload itself (channel from the server); in the foreground
 * it lands here and goes through the same channels, switches and receipt de-duplication as local notifications.
 */
class PushService : FirebaseMessagingService() {
    // FCM calls this on a worker thread, possibly with no activity: hand it to the main thread (the model lives there).
    override fun onNewToken(token: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { (application as SmartBagApp).shop.onPushToken(token) }
    }
    override fun onMessageReceived(m: RemoteMessage) {
        Notify.fromPush(this, m.data, m.notification?.title ?: m.data["title"], m.notification?.body ?: m.data["body"])
    }
}
