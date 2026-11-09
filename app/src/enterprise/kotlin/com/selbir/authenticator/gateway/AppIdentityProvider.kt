package com.selbir.authenticator.gateway

import android.content.Context
import android.content.pm.PackageManager
import com.selbir.authenticator.BuildConfig
import java.security.MessageDigest

/** Package, version and SHA-256 of the signing certificate, as the Gateway records them. */
object AppIdentityProvider {
    fun of(context: Context): AppIdentity = AppIdentity(
        packageName = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        signingDigest = signingDigest(context),
    )

    private fun signingDigest(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return ""
        val first = signers.firstOrNull() ?: return ""
        MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}
