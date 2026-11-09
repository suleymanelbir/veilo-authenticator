package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.gateway.GatewayClient

/** The public edition: offline, no Gateway. A Veilo QR enrols a plain TOTP account. */
object Edition {
    const val CONNECTED = false
    const val ALLOW_PLAIN_HTTP_GATEWAY = false

    @Suppress("UNUSED_PARAMETER")
    fun gateway(context: Context): GatewayClient? = null
}
