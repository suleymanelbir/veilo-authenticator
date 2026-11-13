package com.selbir.authenticator.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** FCM entry point: a new token is stored (registered at the next unlock); a wake-up shows the notification. */
class WakeService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.storeToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // the whole message is {"t":"c"}; anything else is ignored
        if (message.data["t"] == "c") Push.showWake(applicationContext)
    }
}
