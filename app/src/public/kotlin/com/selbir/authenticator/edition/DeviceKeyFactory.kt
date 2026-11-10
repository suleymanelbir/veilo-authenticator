package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.gateway.AppIdentity
import com.selbir.authenticator.gateway.DeviceKeyMaterial
import com.selbir.authenticator.gateway.DeviceSigner

/** Never called in the public edition: [Edition.gateway] is null, so no enrolment is confirmed. */
object DeviceKeyFactory {
    fun alias(accountId: String) = "veilo.device.$accountId"
    fun generate(alias: String, challenge: ByteArray, strongBoxAvailable: Boolean): DeviceKeyMaterial =
        throw UnsupportedOperationException("public edition")
    fun delete(alias: String) = Unit
    fun signer(alias: String): DeviceSigner = throw UnsupportedOperationException("public edition")
    fun appIdentity(context: Context): AppIdentity = throw UnsupportedOperationException("public edition")
}
