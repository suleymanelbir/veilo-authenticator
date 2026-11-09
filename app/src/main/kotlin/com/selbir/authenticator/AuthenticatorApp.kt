package com.selbir.authenticator

import android.app.Application
import android.content.pm.PackageManager
import com.selbir.authenticator.data.AccountRepository
import com.selbir.authenticator.data.AppPreferences
import com.selbir.authenticator.data.EncryptedAccountStore
import com.selbir.authenticator.policy.PolicySource
import com.selbir.authenticator.policy.RestrictionsPolicySource
import com.selbir.authenticator.security.KeystoreSecretStore
import com.selbir.authenticator.security.KeystoreVaultCipher
import java.io.File

class AuthenticatorApp : Application() {
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    /** Organisation rules from managed configuration; [com.selbir.authenticator.policy.Policy.NONE] on an unmanaged device. */
    val policies: PolicySource by lazy { RestrictionsPolicySource(this) }

    val repository: AccountRepository by lazy {
        val strongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val store = EncryptedAccountStore(
            file = File(filesDir, "accounts.vault"),
            legacyFile = File(filesDir, "accounts.json"),
            cipher = KeystoreVaultCipher(),
        )
        AccountRepository(store, KeystoreSecretStore(strongBox))
    }
}
