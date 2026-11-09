package com.selbir.authenticator

import android.app.Application
import android.content.pm.PackageManager
import com.selbir.authenticator.data.AccountRepository
import com.selbir.authenticator.data.AppPreferences
import com.selbir.authenticator.data.EncryptedAccountStore
import com.selbir.authenticator.security.KeystoreSecretStore
import com.selbir.authenticator.security.KeystoreVaultCipher
import java.io.File

class AuthenticatorApp : Application() {
    val preferences: AppPreferences by lazy { AppPreferences(this) }

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
