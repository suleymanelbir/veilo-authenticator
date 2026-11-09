package com.selbir.authenticator.data

import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.security.VaultCipher
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedAccountStoreTest {
    /** Stands in for the Keystore: a fixed 12-byte "nonce", XOR "encryption", and the AAD checked on open. */
    private class FakeVaultCipher : VaultCipher {
        var openCalls = 0

        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = ByteArray(12) { 7 } + xor(plaintext) + aad

        override fun open(blob: ByteArray, aad: ByteArray): ByteArray {
            openCalls++
            val tail = blob.copyOfRange(blob.size - aad.size, blob.size)
            if (!tail.contentEquals(aad)) throw javax.crypto.AEADBadTagException("aad mismatch")
            return xor(blob.copyOfRange(12, blob.size - aad.size))
        }

        private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
    }

    private val dir = Files.createTempDirectory("vault").toFile()
    private val file = File(dir, "accounts.vault")
    private val legacy = File(dir, "accounts.json")
    private val cipher = FakeVaultCipher()
    private val store = EncryptedAccountStore(file, legacy, cipher)

    private fun account(n: Int) = Account(
        id = "id-$n",
        type = OtpType.TOTP,
        issuer = "Service $n",
        name = "user$n@example.com",
        algorithm = OtpAlgorithm.SHA1,
        digits = 6,
        period = 30,
        protection = Protection.EXPORTABLE,
        pinned = n == 1,
        wrappedSecret = "d3JhcHBlZC0k$n",
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun emptyUntilSomethingIsSaved() {
        assertEquals(emptyList(), store.load())
        assertFalse(file.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun savedAccountsComeBackIntact() {
        val accounts = listOf(account(1), account(2))
        store.save(accounts)

        assertEquals(accounts, EncryptedAccountStore(file, legacy, FakeVaultCipher()).load())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun nothingReadableIsLeftOnDisk() {
        store.save(listOf(account(1)))

        val raw = file.readBytes().decodeToString()
        assertTrue(raw.startsWith("VLT1"))
        assertFalse("Service 1" in raw)
        assertFalse("user1@example.com" in raw)
        assertFalse("d3JhcHBlZC0k" in raw)
    }

    @Test
    fun plaintextFileFromEarlierVersionsIsMovedIntoTheVault() {
        legacy.writeText("""{"version":1,"accounts":[${account(1).toJson()},${account(2).toJson()}]}""")

        val loaded = store.load()

        assertEquals(listOf(account(1), account(2)), loaded)
        assertTrue(file.exists())
        assertFalse(legacy.exists())
        assertEquals(loaded, EncryptedAccountStore(file, legacy, FakeVaultCipher()).load())
    }

    @Test
    fun vaultWinsOverALeftoverPlaintextFile() {
        store.save(listOf(account(1)))
        legacy.writeText("""{"version":1,"accounts":[${account(9).toJson()}]}""")

        assertEquals(listOf(account(1)), store.load())
        assertFalse(legacy.exists())
    }

    @Test
    fun unfinishedSaveIsDiscarded() {
        store.save(listOf(account(1)))
        File(file.path + ".tmp").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(listOf(account(1)), store.load())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun tamperedOrForeignFileIsRefusedNotMisread() {
        store.save(listOf(account(1)))
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte() // breaks the authenticated header copy
        file.writeBytes(bytes)
        assertFailsWith<javax.crypto.AEADBadTagException> { store.load() }

        file.writeText("""{"version":1,"accounts":[]}""")
        assertFailsWith<IllegalArgumentException> { store.load() }
    }

    @Test
    fun failedSaveKeepsThePreviousList() {
        store.save(listOf(account(1)))
        val failing = EncryptedAccountStore(file, legacy, object : VaultCipher {
            override fun seal(plaintext: ByteArray, aad: ByteArray) = throw IllegalStateException("keystore down")
            override fun open(blob: ByteArray, aad: ByteArray) = cipher.open(blob, aad)
        })

        assertFailsWith<IllegalStateException> { failing.save(listOf(account(2))) }

        assertEquals(listOf(account(1)), failing.load())
    }

    private fun Account.toJson() =
        """{"id":"$id","type":"TOTP","issuer":"$issuer","name":"$name","algorithm":"SHA1","digits":6,"period":30,""" +
            """"counter":0,"protection":"EXPORTABLE","group":"","pinned":$pinned,"wrappedSecret":"$wrappedSecret"}"""
}
