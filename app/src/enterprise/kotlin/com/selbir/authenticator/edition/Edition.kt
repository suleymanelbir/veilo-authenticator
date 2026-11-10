package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.BuildConfig
import com.selbir.authenticator.gateway.GatewayClient
import com.selbir.authenticator.gateway.HttpGatewayClient

/** The Enterprise flavour: confirms Gateway enrolments. Debug builds may talk to a plain-http lab Gateway. */
object Edition {
    const val CONNECTED = true
    val ALLOW_PLAIN_HTTP_GATEWAY: Boolean = BuildConfig.DEBUG

    fun gateway(context: Context): GatewayClient = HttpGatewayClient(versionName = BuildConfig.VERSION_NAME)
}
