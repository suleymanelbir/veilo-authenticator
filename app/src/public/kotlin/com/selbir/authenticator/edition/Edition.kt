package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.gateway.GatewayClient

/** The public edition: offline, no Gateway. A Veilo QR enrols a plain TOTP account. */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
object Edition {
    const val CONNECTED = false
    const val ALLOW_PLAIN_HTTP_GATEWAY = false
    const val PUSH_CAPABLE = false

    fun gateway(context: Context): GatewayClient? = null

    fun onAppStart(context: Context) = Unit

    fun pushRegistration(context: Context): Pair<String, String>? = null

    fun pushRegisteredWith(context: Context, binding: String): String? = null

    fun markPushRegistered(context: Context, binding: String, token: String) = Unit

    fun clearWake(context: Context) = Unit
}
