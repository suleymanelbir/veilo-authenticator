package com.selbir.authenticator.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** Not one of our containers at all. */
        NOT_A_BACKUP,

        /** One of ours, but a version or parameter set this build cannot read. */
        UNSUPPORTED,

        /** Authentication failed. The two cannot be told apart, by design of the cipher. */
        WRONG_PASSPHRASE_OR_CORRUPT,
    }
}

/**
 * Passphrase-encrypted backup container: Argon2id key derivation + AES-256-GCM.
 *
 * Layout (big-endian), with the whole 42-byte header authenticated as GCM AAD:
 * ```
 * magic "OTPB" (4) | version (1) | memoryKiB (4) | iterations (4) | parallelism (1)
 * | salt (16) | nonce (12) | ciphertext + 16-byte tag
 * ```
 * The KDF cost travels in the header so it can be raised later without a format change.
 */
object BackupCodec {
    class Params(
        val memoryKiB: Int = 64 * 1024,
        val iterations: Int = 3,
        val parallelism: Int = 1,
    )

    private val MAGIC = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 4 + 1 + 4 + 4 + 1 + SALT_LEN + NONCE_LEN

    // Upper bounds enforced on decrypt so a hostile file cannot exhaust memory or CPU.
    private const val MAX_MEMORY_KIB = 1024 * 1024
    private const val MAX_ITERATIONS = 16
    private const val MAX_PARALLELISM = 8

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        params: Params = Params(),
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION)
            .putInt(params.memoryKiB).putInt(params.iterations).put(params.parallelism.toByte())
            .put(salt).put(nonce)
            .array()

        val key = deriveKey(passphrase, salt, params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** @throws BackupException if the file is malformed, or the passphrase is wrong. */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        val header = readHeader(blob)
        val key = deriveKey(passphrase, header.salt, header.params)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, header.nonce))
            cipher.updateAAD(blob, 0, HEADER_LEN)
            return cipher.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_CORRUPT, "Wrong passphrase or corrupted backup", e)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Cheap check, without the passphrase, that [blob] is a container this build can read.
     *
     * @throws BackupException with [BackupException.Reason.NOT_A_BACKUP] or
     * [BackupException.Reason.UNSUPPORTED].
     */
    fun checkHeader(blob: ByteArray) {
        readHeader(blob)
    }

    /**
     * Turns typed text into the passphrase characters fed to the KDF. NFKC makes the same
     * visible passphrase produce the same key whichever keyboard or platform composed it
     * (for example "ş" as one code point or as "s" plus a combining cedilla).
     */
    fun passphraseChars(text: CharSequence): CharArray = Normalizer.normalize(text, Normalizer.Form.NFKC).toCharArray()

    private class Header(val params: Params, val salt: ByteArray, val nonce: ByteArray)

    private fun readHeader(blob: ByteArray): Header {
        if (blob.size < HEADER_LEN + TAG_BITS / 8) throw notABackup()
        val buf = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC.size).also(buf::get)
        if (!magic.contentEquals(MAGIC)) throw notABackup()
        if (buf.get() != VERSION) throw BackupException(BackupException.Reason.UNSUPPORTED, "Unsupported backup version")

        val params = Params(memoryKiB = buf.getInt(), iterations = buf.getInt(), parallelism = buf.get().toInt())
        if (params.memoryKiB !in 8..MAX_MEMORY_KIB ||
            params.iterations !in 1..MAX_ITERATIONS ||
            params.parallelism !in 1..MAX_PARALLELISM
        ) throw BackupException(BackupException.Reason.UNSUPPORTED, "Backup file has unsupported parameters")

        return Header(params, salt = ByteArray(SALT_LEN).also(buf::get), nonce = ByteArray(NONCE_LEN).also(buf::get))
    }

    private fun notABackup() = BackupException(BackupException.Reason.NOT_A_BACKUP, "Not a backup file")

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, params: Params): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
        )
        return ByteArray(32).also { generator.generateBytes(passphrase, it) }
    }
}
