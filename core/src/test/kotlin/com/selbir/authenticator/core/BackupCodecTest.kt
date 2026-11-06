package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupCodecTest {
    // Tiny KDF cost keeps the tests fast; production defaults are in BackupCodec.Params.
    private val fast = BackupCodec.Params(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val plaintext = """{"accounts":[{"issuer":"Example"}]}""".toByteArray()

    @Test
    fun roundTrips() {
        val blob = BackupCodec.encrypt(plaintext, "correct horse".toCharArray(), fast)
        assertContentEquals(plaintext, BackupCodec.decrypt(blob, "correct horse".toCharArray()))
    }

    @Test
    fun everyEncryptionIsUnique() {
        val a = BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast)
        val b = BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun rejectsWrongPassphrase() {
        val blob = BackupCodec.encrypt(plaintext, "right".toCharArray(), fast)
        assertFailsWith<BackupException> { BackupCodec.decrypt(blob, "wrong".toCharArray()) }
    }

    @Test
    fun detectsTamperingAnywhereInTheFile() {
        val blob = BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast)
        // One offset in each region: KDF parameters, salt, nonce, ciphertext, tag.
        for (offset in listOf(12, 20, 35, 45, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[offset] = (it[offset].toInt() xor 0x01).toByte() }
            assertFailsWith<BackupException>("offset=$offset") { BackupCodec.decrypt(tampered, "pw".toCharArray()) }
        }
    }

    @Test
    fun rejectsForeignAndHostileFiles() {
        assertFailsWith<BackupException> { BackupCodec.decrypt(ByteArray(10), "pw".toCharArray()) }
        assertFailsWith<BackupException> { BackupCodec.decrypt(ByteArray(100), "pw".toCharArray()) }

        // A header demanding ~2 TiB of memory must be refused before any derivation runs.
        val blob = BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast)
        blob[5] = 0x7F
        assertFailsWith<BackupException> { BackupCodec.decrypt(blob, "pw".toCharArray()) }
    }

    @Test
    fun reportsWhyAFileWasRefused() {
        fun reasonFor(blob: ByteArray, passphrase: String = "pw") =
            assertFailsWith<BackupException> { BackupCodec.decrypt(blob, passphrase.toCharArray()) }.reason

        val blob = BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast)
        assertEquals(BackupException.Reason.NOT_A_BACKUP, reasonFor(ByteArray(100)))
        assertEquals(BackupException.Reason.UNSUPPORTED, reasonFor(blob.copyOf().also { it[4] = 2 }))
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE_OR_CORRUPT, reasonFor(blob, "other"))
    }

    @Test
    fun checksTheHeaderWithoutThePassphrase() {
        BackupCodec.checkHeader(BackupCodec.encrypt(plaintext, "pw".toCharArray(), fast))
        assertEquals(
            BackupException.Reason.NOT_A_BACKUP,
            assertFailsWith<BackupException> { BackupCodec.checkHeader("%PDF-1.7".toByteArray() + ByteArray(100)) }.reason,
        )
    }

    @Test
    fun passphraseDoesNotDependOnHowItWasComposed() {
        // "ş" precomposed, and as "s" followed by a combining cedilla.
        val blob = BackupCodec.encrypt(plaintext, BackupCodec.passphraseChars("\u015Fifre"), fast)
        assertContentEquals(plaintext, BackupCodec.decrypt(blob, BackupCodec.passphraseChars("s\u0327ifre")))
    }
}
