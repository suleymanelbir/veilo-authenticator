package com.selbir.authenticator.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.selbir.authenticator.BuildConfig
import com.selbir.authenticator.MainActivity
import com.selbir.authenticator.R

/**
 * 1.1 push (Enterprise only, PROTOCOL.md §5.2). The Gateway sends a content-free wake-up
 * when a sign-in waits for this phone; we show a notification that says only "a sign-in is
 * waiting". Tapping it opens the app, which unlocks and polls as before — the number, the
 * application and the signature never travel through the push provider, and the device key
 * (usable only while the phone is unlocked) is never touched in the background.
 *
 * Firebase is initialised by hand from BuildConfig (no google-services plugin, no
 * analytics); without a configured project nothing is registered and polling still works.
 */
object Push {
    const val KIND_FCM = "fcm"
    private const val CHANNEL = "signin"
    private const val NOTIFICATION_ID = 4711
    private const val PREFS = "push"
    private const val KEY_TOKEN = "fcm_token"
    private const val WAKE_TIMEOUT_MS = 120_000L

    val configured: Boolean
        get() = BuildConfig.FCM_APP_ID.isNotEmpty() && BuildConfig.FCM_PROJECT_ID.isNotEmpty() &&
            BuildConfig.FCM_API_KEY.isNotEmpty() && BuildConfig.FCM_SENDER_ID.isNotEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** At app start: initialise Firebase (if configured) and fetch the token. */
    fun start(context: Context) {
        if (!configured) return
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isEmpty()) {
            FirebaseApp.initializeApp(
                app,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build(),
            )
        }
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.token.addOnSuccessListener { token -> if (!token.isNullOrBlank()) storeToken(app, token) }
        ensureChannel(app)
    }

    fun storeToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    /** ("fcm", token) once Firebase gave us one; null otherwise. */
    fun registration(context: Context): Pair<String, String>? =
        prefs(context).getString(KEY_TOKEN, null)?.let { KIND_FCM to it }

    /** What was last registered with a Gateway binding, so we re-register only on change. */
    fun registeredWith(context: Context, binding: String): String? = prefs(context).getString("reg:$binding", null)

    fun markRegistered(context: Context, binding: String, token: String) {
        prefs(context).edit().putString("reg:$binding", token).apply()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.push_channel_description)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC   // the text reveals nothing
            },
        )
    }

    /** The wake-up arrived: "a sign-in is waiting", gone by itself after the challenge's 2 minutes. */
    fun showWake(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.push_title))
            .setContentText(context.getString(R.string.push_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(WAKE_TIMEOUT_MS)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
    }

    /** The app is open and showing the request: the notification is no longer needed. */
    fun clearWake(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
