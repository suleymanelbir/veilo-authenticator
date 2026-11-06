package com.selbir.authenticator.security

import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.RequiresApi
import com.selbir.authenticator.core.HmacFunction
import com.selbir.authenticator.core.OtpAlgorithm
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Key material at rest. Two storage modes:
 *
 * - **Hardware-bound**: the OTP secret itself is imported into the Android Keystore as a
 *   non-exportable HMAC key. Codes are computed inside secure hardware (the StrongBox
 *   security chip where the device has one that can do it, otherwise the TEE); the secret
 *   never returns to app memory and cannot be backed up.
 * - **Wrapped**: the OTP secret is encrypted with an AES-256-GCM key that lives in the
 *   Keystore. The secret is in app memory only while a code is being computed, and can
 *   be re-encrypted into a backup.
 *
 * Every key requires the user to have authenticated (strong biometric or device
 * credential) recently. The Keystore enforces that, not this app: operations outside
 * the window throw `UserNotAuthenticatedException`.
 */
/** Where the Keystore holds a key, as the Keystore itself reports it. */
enum class KeyLevel {
    /** A dedicated, tamper-resistant security chip. */
    STRONGBOX,

    /** The processor's trusted execution environment. */
    SECURE_HARDWARE,

    /** Not in secure hardware: protected by the operating system only. */
    SOFTWARE,

    UNKNOWN,
}

interface SecretStore {
    /**
     * Moves [secret] into the Keystore under [alias]. The Keystore rejects HMAC keys
     * shorter than 64 bits, so very short secrets can only be stored wrapped.
     *
     * Can throw `UserNotAuthenticatedException`: the key is tried out once after import.
     */
    fun importHmacKey(alias: String, secret: ByteArray, algorithm: OtpAlgorithm)

    /** Where the key imported under [alias] is held. Reads metadata only; needs no authentication. */
    fun keyLevel(alias: String): KeyLevel

    /** Where the key behind [wrap] is held, or null if nothing has been wrapped yet. */
    fun wrappingKeyLevel(): KeyLevel?

    /** HMAC computed inside secure hardware with the key imported under [alias]. */
    fun hmac(alias: String, algorithm: OtpAlgorithm): HmacFunction

    /** Returns `nonce || ciphertext || tag`. */
    fun wrap(secret: ByteArray): ByteArray

    /** Inverse of [wrap]. The caller must wipe the returned array after use. */
    fun unwrap(blob: ByteArray): ByteArray

    fun delete(alias: String)
}

/** Remembers an event for [windowMs], on whatever clock the caller reads `now` from. */
internal class RetryWindow(private val windowMs: Long) {
    @Volatile
    private var markedAt: Long? = null

    fun mark(now: Long) {
        markedAt = now
    }

    fun isOpen(now: Long): Boolean {
        val at = markedAt ?: return false
        return now - at < windowMs
    }
}

/**
 * [SecretStore] on the Android Keystore. The only class that touches key material at rest.
 *
 * @param strongBoxAvailable whether the device advertises a StrongBox security chip.
 */
class KeystoreSecretStore(private val strongBoxAvailable: Boolean) : SecretStore {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Open for a short while after a StrongBox trial failed for want of authentication. */
    private val strongBoxAuthRetry = RetryWindow(AUTH_RETRY_WINDOW_MS)

    override fun importHmacKey(alias: String, secret: ByteArray, algorithm: OtpAlgorithm) {
        // An imported key can only be directed to StrongBox from Android 12.
        if (strongBoxAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            importIntoStrongBox(alias, secret, algorithm)
        ) {
            return
        }
        keyStore.setEntry(alias, hmacEntry(secret, algorithm), hmacProtection().build())
    }

    /**
     * Tries the security chip first. StrongBox is only required to support HMAC-SHA-256,
     * and a chip may accept a key it then cannot use, so the key is tried out before it is
     * trusted. Returns false when the caller should fall back to the TEE.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun importIntoStrongBox(alias: String, secret: ByteArray, algorithm: OtpAlgorithm): Boolean {
        // Trying the key out needs a fresh authentication. If it was refused a moment ago,
        // the caller has just re-authenticated; a second refusal means this chip does not
        // honour the authentication window, and asking again would never end.
        val retryingAfterAuth = strongBoxAuthRetry.isOpen(SystemClock.elapsedRealtime())
        try {
            keyStore.setEntry(alias, hmacEntry(secret, algorithm), hmacProtection().setIsStrongBoxBacked(true).build())
            hmac(alias, algorithm).compute(ByteArray(8))
            return true
        } catch (e: UserNotAuthenticatedException) {
            deleteQuietly(alias)
            if (retryingAfterAuth) return false
            strongBoxAuthRetry.mark(SystemClock.elapsedRealtime())
            throw e
        } catch (e: Exception) {
            deleteQuietly(alias)
            return false
        }
    }

    private fun hmacEntry(secret: ByteArray, algorithm: OtpAlgorithm) =
        KeyStore.SecretKeyEntry(SecretKeySpec(secret, algorithm.jcaName))

    private fun hmacProtection() = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, AUTH_TYPES)

    private fun deleteQuietly(alias: String) {
        try {
            keyStore.deleteEntry(alias)
        } catch (ignored: Exception) {
        }
    }

    override fun keyLevel(alias: String): KeyLevel = levelOf(alias) ?: KeyLevel.UNKNOWN

    override fun wrappingKeyLevel(): KeyLevel? = levelOf(WRAP_ALIAS)

    /** Null when there is no key under [alias]. */
    private fun levelOf(alias: String): KeyLevel? = try {
        val key = keyStore.getKey(alias, null) as? SecretKey
        if (key == null) {
            null
        } else {
            val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                    -> KeyLevel.SECURE_HARDWARE
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyLevel.SOFTWARE
                    else -> KeyLevel.UNKNOWN
                }
            } else {
                // Android 11 cannot tell the security chip from the TEE.
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyLevel.SECURE_HARDWARE else KeyLevel.SOFTWARE
            }
        }
    } catch (e: Exception) {
        KeyLevel.UNKNOWN
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

        /** Long enough for one authentication prompt, far shorter than [AUTH_VALIDITY_SECONDS]. */
        private const val AUTH_RETRY_WINDOW_MS = 60_000L

        private const val AUTH_TYPES = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        private const val PROVIDER = "AndroidKeyStore"
        private const val WRAP_ALIAS = "wrap.v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
