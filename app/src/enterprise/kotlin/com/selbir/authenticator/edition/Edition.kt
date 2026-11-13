package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.BuildConfig
import com.selbir.authenticator.gateway.GatewayClient
import com.selbir.authenticator.gateway.HttpGatewayClient
import com.selbir.authenticator.push.Push

/** The Enterprise flavour: confirms Gateway enrolments. Debug builds may talk to a plain-http lab Gateway. */
object Edition {
    const val CONNECTED = true
    val ALLOW_PLAIN_HTTP_GATEWAY: Boolean = BuildConfig.DEBUG

    fun gateway(context: Context): GatewayClient = HttpGatewayClient(versionName = BuildConfig.VERSION_NAME)

    /** 1.1 push: Firebase (when configured) at app start. */
    fun onAppStart(context: Context) = Push.start(context)

    /** Whether to ask for the notification permission (only when push can work at all). */
    val PUSH_CAPABLE: Boolean get() = Push.configured

    /** (kind, token) to register with each Gateway binding, or null. */
    fun pushRegistration(context: Context): Pair<String, String>? = Push.registration(context)

    fun pushRegisteredWith(context: Context, binding: String): String? = Push.registeredWith(context, binding)

    fun markPushRegistered(context: Context, binding: String, token: String) = Push.markRegistered(context, binding, token)

    fun clearWake(context: Context) = Push.clearWake(context)
}
