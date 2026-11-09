package com.selbir.authenticator.data

import com.selbir.authenticator.security.VaultCipher
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the account list is persisted. */
interface AccountStore {
    fun load(): List<Account>

    fun save(accounts: List<Account>)
}

/**
 * Persists the account list as one encrypted file in app-private storage.
 *
 * File layout: `magic "VLT1" (4) | nonce (12) | ciphertext + tag`, the magic authenticated as
 * GCM AAD. The plaintext is the [Vault] JSON. Writes go to a temporary file that is then
 * renamed over the old one, so a crash mid-save leaves the previous list intact.
 *
 * Versions up to 0.2.0 kept the same JSON unencrypted in [legacyFile]. The first load that
 * finds it moves the list into the encrypted file and wipes the old one.
 */
class EncryptedAccountStore(
    private val file: File,
    private val legacyFile: File,
    private val cipher: VaultCipher,
) : AccountStore {
    private val tempFile = File(file.path + ".tmp")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    override fun load(): List<Account> {
        // A leftover temporary file is a save that never completed: the real file is intact.
        tempFile.delete()

        if (file.exists()) {
            val accounts = decode(cipher.open(stripMagic(file.readBytes()), MAGIC))
            // Can only be left over from a migration interrupted between write and wipe.
            if (legacyFile.exists()) wipe(legacyFile)
            return accounts
        }
        if (legacyFile.exists()) {
            val accounts = decode(legacyFile.readBytes())
            save(accounts)
            wipe(legacyFile)
            return accounts
        }
        return emptyList()
    }

    @Synchronized
    override fun save(accounts: List<Account>) {
        val plaintext = json.encodeToString(Vault(accounts = accounts)).encodeToByteArray()
        val blob = try {
            MAGIC + cipher.seal(plaintext, MAGIC)
        } finally {
            plaintext.fill(0)
        }
        FileOutputStream(tempFile).use { out ->
            out.write(blob)
            out.fd.sync()
        }
        Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun decode(plaintext: ByteArray): List<Account> = try {
        json.decodeFromString<Vault>(plaintext.decodeToString()).accounts
    } finally {
        plaintext.fill(0)
    }

    private fun stripMagic(bytes: ByteArray): ByteArray {
        require(bytes.size > MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Not an account vault" }
        return bytes.copyOfRange(MAGIC.size, bytes.size)
    }

    /** Best effort: overwrites before deleting, in case the file system ever hands the blocks out again. */
    private fun wipe(target: File) {
        try {
            FileOutputStream(target).use { out ->
                out.write(ByteArray(target.length().toInt().coerceAtLeast(0)))
                out.fd.sync()
            }
        } catch (ignored: Exception) {
        }
        target.delete()
    }

    private companion object {
        val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    }
}
