package com.selbir.authenticator.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the current [Policy] comes from. */
interface PolicySource {
    val policy: StateFlow<Policy>

    /** Re-reads the policy; cheap, called on every unlock. */
    fun refresh()
}

/**
 * [PolicySource] on Android managed configuration ([RestrictionsManager]). The MDM writes the
 * restrictions into the device's work profile or managed device; Android hands them to the app
 * without any network access on the app's part. Changes arrive as a system broadcast while the
 * app runs, and are re-read on unlock in case one was missed.
 */
class RestrictionsPolicySource(private val context: Context) : PolicySource {
    private val manager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    private val _policy = MutableStateFlow(read())
    override val policy: StateFlow<Policy> = _policy.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // a system broadcast; nothing else may send it
        )
    }

    override fun refresh() {
        _policy.value = read()
    }

    private fun read(): Policy = Policy.from(manager.applicationRestrictions.toMap())

    private fun Bundle.toMap(): Map<String, Any?> = keySet().associateWith { key ->
        @Suppress("DEPRECATION")
        get(key)
    }
}

/** For the public edition's tests and previews: no organisation, nothing restricted. */
class NoPolicySource : PolicySource {
    override val policy: StateFlow<Policy> = MutableStateFlow(Policy.NONE)
    override fun refresh() = Unit
}
