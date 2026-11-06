package com.selbir.authenticator.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import com.selbir.authenticator.core.HmacFunction
import com.selbir.authenticator.core.OtpAlgorithm
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key material at rest. Two storage modes:
 *
 * - **Hardware-bound**: the OTP secret itself is imported into the Android Keystore as a
 *   non-exportable HMAC key. Codes are computed inside the TEE; the secret never returns
 *   to app memory and cannot be backed up.
 * - **Wrapped**: the OTP secret is encrypted with an AES-256-GCM key that lives in the
 *   Keystore. The secret is in app memory only while a code is being computed, and can
 *   be re-encrypted into a backup.
 *
 * Every key requires the user to have authenticated (strong biometric or device
 * credential) recently. The Keystore enforces that, not this app: operations outside
 * the window throw `UserNotAuthenticatedException`.
 */
interface SecretStore {
    /**
     * Moves [secret] into the Keystore under [alias]. The Keystore rejects HMAC keys
     * shorter than 64 bits, so very short secrets can only be stored wrapped.
     */
    fun importHmacKey(alias: String, secret: ByteArray, algorithm: OtpAlgorithm)

    /** HMAC computed inside secure hardware with the key imported under [alias]. */
    fun hmac(alias: String, algorithm: OtpAlgorithm): HmacFunction

    /** Returns `nonce || ciphertext || tag`. */
    fun wrap(secret: ByteArray): ByteArray

    /** Inverse of [wrap]. The caller must wipe the returned array after use. */
    fun unwrap(blob: ByteArray): ByteArray

    fun delete(alias: String)
}

/** [SecretStore] on the Android Keystore. The only class that touches key material at rest. */
class KeystoreSecretStore : SecretStore {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun importHmacKey(alias: String, secret: ByteArray, algorithm: OtpAlgorithm) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, AUTH_TYPES)
            .build()
        keyStore.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(secret, algorithm.jcaName)), protection)
    }

    override fun hmac(alias: String, algorithm: OtpAlgorithm): HmacFunction = HmacFunction { message ->
        val mac = Mac.getInstance(algorithm.jcaName)
        mac.init(requireKey(alias))
        mac.doFinal(message)
    }

    override fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The screen lock was removed at some point, which destroys auth-bound keys.
            // Secrets wrapped with the old key are already unrecoverable; start a new one.
            keyStore.deleteEntry(WRAP_ALIAS)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        val ciphertext = cipher.doFinal(secret)
        return cipher.iv + ciphertext
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > GCM_NONCE_LEN) { "Wrapped secret is truncated" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, requireKey(WRAP_ALIAS), GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_NONCE_LEN))
        return cipher.doFinal(blob, GCM_NONCE_LEN, blob.size - GCM_NONCE_LEN)
    }

    override fun delete(alias: String) {
        keyStore.deleteEntry(alias)
    }

    private fun requireKey(alias: String): SecretKey =
        keyStore.getKey(alias, null) as? SecretKey ?: throw KeyStoreException("No key under alias $alias")

    @Synchronized
    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, AUTH_TYPES)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        /** How long one authentication keeps the keys usable. */
        const val AUTH_VALIDITY_SECONDS = 120

        private const val AUTH_TYPES = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        private const val PROVIDER = "AndroidKeyStore"
        private const val WRAP_ALIAS = "wrap.v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
