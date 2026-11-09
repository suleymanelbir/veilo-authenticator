package com.selbir.authenticator.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the account list file at rest. See `data/EncryptedAccountStore`.
 *
 * This protects the metadata (issuer, account name, parameters) and the already
 * Keystore-wrapped secrets from being read off the file system. It is a second layer:
 * the OTP secrets inside stay wrapped under the authentication-bound key in [SecretStore].
 */
interface VaultCipher {
    /** Returns `nonce || ciphertext || tag`. [aad] is authenticated but not encrypted. */
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Inverse of [seal]. Throws if [blob] was not produced by this device's key or was altered. */
    fun open(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * [VaultCipher] on an AES-256-GCM key in the Android Keystore.
 *
 * Unlike the keys in [KeystoreSecretStore] this key does not require user authentication:
 * the account list is read on every unlock, and the Keystore reports a lapsed authentication
 * only after the user has already seen the list. Losing the screen lock therefore leaves the
 * list readable, which is what the user expects: the secrets inside are unusable anyway.
 */
class KeystoreVaultCipher : VaultCipher {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > NONCE_LEN) { "Vault is truncated" }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: throw KeyStoreException("Vault key is missing")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, NONCE_LEN))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
    }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "vault.v1"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val NONCE_LEN = 12
        const val TAG_BITS = 128
    }
}
