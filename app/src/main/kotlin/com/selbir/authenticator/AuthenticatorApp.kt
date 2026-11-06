package com.selbir.authenticator

import android.app.Application
import android.content.pm.PackageManager
import com.selbir.authenticator.data.AccountRepository
import com.selbir.authenticator.data.FileAccountStore
import com.selbir.authenticator.security.KeystoreSecretStore
import java.io.File

class AuthenticatorApp : Application() {
    val repository: AccountRepository by lazy {
        val strongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        AccountRepository(FileAccountStore(File(filesDir, "accounts.json")), KeystoreSecretStore(strongBox))
    }
}
