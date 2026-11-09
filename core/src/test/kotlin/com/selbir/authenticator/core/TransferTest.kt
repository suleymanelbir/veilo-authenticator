package com.selbir.authenticator.core

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferTest {
    private val receiver = Transfer.newKeyPair()
    private val payload = BackupPayload.encode(
        listOf(
            OtpSpec(OtpType.TOTP, "Service", "user@example.com", ByteArray(20) { it.toByte() }),
            OtpSpec(OtpType.HOTP, "Counter", "user", ByteArray(32) { (it * 3).toByte() }, OtpAlgorithm.SHA256, 8, counter = 42),
        ),
    )

    @Test
    fun receiverCodeRoundTrips() {
        val code = Transfer.receiverCode(receiver.public)
        assertTrue(code.startsWith("VTR1:"))
        assertEquals(receiver.public, Transfer.parseReceiverCode(code))
    }

    @Test
    fun onlyTheReceiverCanDecrypt() {
        val blob = Transfer.encrypt(payload, receiver.public)

        assertContentEquals(payload, Transfer.decrypt(blob, receiver))
        assertFailsWith<TransferException> { Transfer.decrypt(blob, Transfer.newKeyPair()) }
    }

    @Test
    fun eachTransferUsesAFreshKeyAndNonce() {
        val a = Transfer.encrypt(payload, receiver.public)
        val b = Transfer.encrypt(payload, receiver.public)
        assertFalse(a.contentEquals(b))
        // The same plaintext is never recognisable from the ciphertext.
        assertFalse(a.copyOfRange(2 + 91 + 12, a.size).contentEquals(b.copyOfRange(2 + 91 + 12, b.size)))
    }

    @Test
    fun alteredBlobIsRejected() {
        val blob = Transfer.encrypt(payload, receiver.public)
        blob[blob.size - 1] = (blob.last().toInt() xor 1).toByte()
        assertFailsWith<TransferException> { Transfer.decrypt(blob, receiver) }
        assertFailsWith<TransferException> { Transfer.decrypt(blob.copyOf(40), receiver) }
        assertFailsWith<TransferException> { Transfer.decrypt(ByteArray(0), receiver) }
    }

    @Test
    fun garbageIsNotAReceiverCode() {
        assertFailsWith<TransferException> { Transfer.parseReceiverCode("otpauth://totp/x?secret=AAAA") }
        assertFailsWith<TransferException> { Transfer.parseReceiverCode("VTR1:!!!") }
        assertFailsWith<TransferException> { Transfer.parseReceiverCode("VTR1:AAAAAAAA") }
        // A valid key on a different curve must not be accepted either.
        val other = java.security.KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }
        val code = "VTR1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(other.public.encoded)
        assertFailsWith<TransferException> { Transfer.parseReceiverCode(code) }
    }

    @Test
    fun framesReassembleInAnyOrderWithRepeats() {
        val blob = Transfer.encrypt(payload, receiver.public)
        val frames = Transfer.frames(blob, frameChars = 50)
        assertTrue(frames.size > 3)
        assertTrue(frames.all { it.startsWith("VTF1:") && it.length <= 50 + 20 })

        val collector = Transfer.FrameCollector()
        val shuffled = frames.shuffled(java.util.Random(7)) + frames.take(2)
        var accepted = 0
        for (frame in shuffled) {
            assertNull(collector.assembled().takeIf { collector.received < collector.total })
            if (collector.accept(frame)) accepted++
        }
        assertEquals(frames.size, accepted)
        assertEquals(frames.size, collector.total)
        assertContentEquals(blob, collector.assembled())
        assertContentEquals(payload, Transfer.decrypt(collector.assembled()!!, receiver))
    }

    @Test
    fun collectorStartsOverWhenTheSenderDoes() {
        val first = Transfer.frames(Transfer.encrypt(payload, receiver.public), frameChars = 60)
        val second = Transfer.frames(Transfer.encrypt(payload, receiver.public), frameChars = 60)
        val collector = Transfer.FrameCollector()

        assertTrue(collector.accept(first[0]))
        assertTrue(collector.accept(second[0]))
        assertEquals(1, collector.received)
        second.drop(1).forEach { collector.accept(it) }
        assertContentEquals(payload, Transfer.decrypt(collector.assembled()!!, receiver))
    }

    @Test
    fun collectorIgnoresWhatIsNotAFrame() {
        val collector = Transfer.FrameCollector()
        assertFalse(collector.accept("otpauth://totp/x?secret=AAAA"))
        assertFalse(collector.accept("VTF1:abcdef:0/3:xx"))
        assertFalse(collector.accept("VTF1:abcdef:4/3:xx"))
        assertFalse(collector.accept("VTF1:abcdef:1/3:"))
        assertFalse(collector.accept("VTF1:abcdef:1/9999:xx"))
        assertEquals(0, collector.total)
        assertNull(collector.assembled())
    }

    @Test
    fun mixedFramesFromTwoTransfersWithTheSameCountAreCaught() {
        val a = Transfer.frames(Transfer.encrypt(payload, receiver.public), frameChars = 60)
        val b = Transfer.frames(Transfer.encrypt(payload, receiver.public), frameChars = 60)
        assertEquals(a.size, b.size)
        // Forge b's frames to carry a's tag, as a confused or hostile sender might.
        val tagA = a[0].substring(5, 11)
        val forged = b.mapIndexed { i, f -> if (i == 0) f else "VTF1:$tagA" + f.substring(11) }
        val collector = Transfer.FrameCollector()
        collector.accept(a[0])
        forged.drop(1).forEach { collector.accept(it) }
        assertFailsWith<TransferException> { collector.assembled() }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.deriveSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun realisticPayloadFitsInAHandfulOfFrames() {
        val twenty = BackupPayload.encode(List(20) { OtpSpec(OtpType.TOTP, "Service $it", "user$it@example.com", ByteArray(20) { b -> b.toByte() }) })
        val frames = Transfer.frames(Transfer.encrypt(twenty, receiver.public, SecureRandom()))
        assertTrue(frames.size in 5..15, "got ${frames.size} frames")
    }
}
