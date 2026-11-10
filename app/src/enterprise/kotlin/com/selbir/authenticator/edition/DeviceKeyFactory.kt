package com.selbir.authenticator.edition

import android.content.Context
import com.selbir.authenticator.gateway.AppIdentity
import com.selbir.authenticator.gateway.AppIdentityProvider
import com.selbir.authenticator.gateway.DeviceKey
import com.selbir.authenticator.gateway.DeviceKeyMaterial
import com.selbir.authenticator.gateway.DeviceSigner

/** Enterprise flavour: the real Keystore device key and app identity. */
object DeviceKeyFactory {
    fun alias(accountId: String) = DeviceKey.alias(accountId)
    fun generate(alias: String, challenge: ByteArray, strongBoxAvailable: Boolean): DeviceKeyMaterial =
        DeviceKey.generate(alias, challenge, strongBoxAvailable)
    fun delete(alias: String) = DeviceKey.delete(alias)
    fun signer(alias: String): DeviceSigner = DeviceKey.signer(alias)
    fun appIdentity(context: Context): AppIdentity = AppIdentityProvider.of(context)
}
