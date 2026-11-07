package com.selbir.authenticator.security

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import com.selbir.authenticator.R

/**
 * Puts one-time codes on the clipboard and takes them off again [CLEAR_AFTER_SECONDS] later.
 *
 * The clipboard is shared with every app, so a code should not outlive its usefulness
 * there. Three things remove it, whichever comes first:
 *
 * 1. a timer in this process, which is on time but stops if Android freezes or kills the
 *    app while the user is pasting the code elsewhere;
 * 2. a system alarm, which survives that but may be delivered late;
 * 3. the app coming back to the front after the deadline.
 *
 * Android only lets an app *read* the clipboard while it has window focus. So when the
 * deadline arrives in the background the clipboard is cleared without knowing whether it
 * still holds our code; anything the user copied elsewhere in the meantime goes with it.
 * While the app is in front the clip is checked first, and a clip that is no longer ours
 * is left alone.
 *
 * All functions must be called on the main thread.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_SECONDS = 30

    // ClipDescription.EXTRA_IS_SENSITIVE; the constant only exists from API 33 but the key is honoured earlier too.
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** Marks a clip as ours. Its value is the wall-clock time (ms) at which the clip is due to be cleared. */
    private const val EXTRA_CLEAR_AT = "com.selbir.authenticator.CLEAR_AT"

    private val handler = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null
    private var listening = false

    /** Copies [code], flagged sensitive so the system preview and keyboards do not display it. */
    @MainThread
    fun copy(context: Context, code: String, label: String = context.getString(R.string.app_name)) {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java)

        val clip = ClipData.newPlainText(label, code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
            putLong(EXTRA_CLEAR_AT, System.currentTimeMillis() + CLEAR_AFTER_SECONDS * 1000L)
        }
        clipboard.setPrimaryClip(clip)

        if (!listening) {
            // Only delivered while we have focus: the user copied something else inside the app.
            clipboard.addPrimaryClipChangedListener { onFocus(app) }
            listening = true
        }
        schedule(app)
    }

    /**
     * Call when the app's window gains focus, the only time the clipboard can be inspected:
     * clears a code that is past its deadline, and stands down if the clipboard has moved on.
     */
    @MainThread
    fun onFocus(context: Context) {
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        val description = clipboard.readDescription() ?: return // Empty, or not readable right now.
        val clearAt = description.clearAt()
        when {
            clearAt == null -> cancelPending(app) // Not ours any more: nothing left for us to clear.
            System.currentTimeMillis() >= clearAt -> clear(app)
        }
    }

    /** The deadline has arrived (timer or alarm). */
    @MainThread
    internal fun clear(context: Context) {
        val app = context.applicationContext
        cancelPending(app)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        val description = clipboard.readDescription()
        if (description != null && description.clearAt() == null) return // Readable, and it is someone else's.
        try {
            clipboard.clearPrimaryClip()
        } catch (e: RuntimeException) {
            // Some vendor clipboard services reject background writes. The focus check is the fallback.
        }
    }

    private fun schedule(app: Context) {
        cancelPending(app)
        val delayMillis = CLEAR_AFTER_SECONDS * 1000L
        val task = Runnable { clear(app) }
        timer = task
        handler.postDelayed(task, delayMillis)
        // Inexact and non-waking on purpose: needs no permission, and while the device
        // sleeps nothing can read the clipboard anyway.
        app.getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMillis, alarmIntent(app))
    }

    private fun cancelPending(app: Context) {
        timer?.let(handler::removeCallbacks)
        timer = null
        app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app))
    }

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, ClipboardClearReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Null when the clipboard is empty or we are not allowed to look (no window focus). */
    private fun ClipboardManager.readDescription(): ClipDescription? = try {
        primaryClipDescription
    } catch (e: RuntimeException) {
        null
    }

    private fun ClipDescription.clearAt(): Long? =
        extras?.takeIf { it.containsKey(EXTRA_CLEAR_AT) }?.getLong(EXTRA_CLEAR_AT)
}

/** Delivers the system alarm set by [SensitiveClipboard]. Not exported: only this app can trigger it. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SensitiveClipboard.clear(context)
    }
}
