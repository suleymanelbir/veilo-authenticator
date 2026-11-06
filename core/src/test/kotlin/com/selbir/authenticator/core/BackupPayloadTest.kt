package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupPayloadTest {
    private val totp = OtpSpec(OtpType.TOTP, "Örnek: A.Ş.", "kişi@example.com", "12345678901234567890".toByteArray())
    private val hotp = OtpSpec(
        type = OtpType.HOTP,
        issuer = "",
        accountName = "name \"with\" quotes",
        secret = ByteArray(64) { it.toByte() },
        algorithm = OtpAlgorithm.SHA512,
        digits = 8,
        period = 60,
        counter = 41,
    )

    @Test
    fun roundTripsEveryField() {
        val decoded = BackupPayload.decode(BackupPayload.encode(listOf(totp, hotp)))
        assertEquals(2, decoded.size)
        for ((expected, actual) in listOf(totp, hotp).zip(decoded)) {
            assertEquals(expected.type, actual.type)
            assertEquals(expected.issuer, actual.issuer)
            assertEquals(expected.accountName, actual.accountName)
            assertContentEquals(expected.secret, actual.secret)
            assertEquals(expected.algorithm, actual.algorithm)
            assertEquals(expected.digits, actual.digits)
            assertEquals(expected.period, actual.period)
            assertEquals(expected.counter, actual.counter)
        }
    }

    @Test
    fun roundTripsAnEmptyList() {
        assertTrue(BackupPayload.decode(BackupPayload.encode(emptyList())).isEmpty())
    }

    @Test
    fun survivesTheEncryptedContainer() {
        val fast = BackupCodec.Params(memoryKiB = 64, iterations = 1, parallelism = 1)
        val blob = BackupCodec.encrypt(BackupPayload.encode(listOf(totp)), "pw".toCharArray(), fast)
        val restored = BackupPayload.decode(BackupCodec.decrypt(blob, "pw".toCharArray())).single()
        // The property that matters: the restored account produces the same codes.
        assertEquals(
            Otp.totp(Otp.softwareHmac(totp.secret, totp.algorithm), 59, 30, 6),
            Otp.totp(Otp.softwareHmac(restored.secret, restored.algorithm), 59, 30, 6),
        )
    }

    @Test
    fun appliesDefaultsAndIgnoresUnknownFields() {
        val spec = BackupPayload.decode(
            """{"version":1,"future":true,"accounts":[{"type":"TOTP","secret":"GEZDGNBV","icon":"x"}]}""".toByteArray()
        ).single()
        assertEquals(OtpAlgorithm.SHA1, spec.algorithm)
        assertEquals(6, spec.digits)
        assertEquals(30, spec.period)
        assertContentEquals("12345".toByteArray(), spec.secret)
    }

    @Test
    fun rejectsWhatItCannotRead() {
        val bad = listOf(
            "not json",
            """{"version":2,"accounts":[]}""",
            """{"version":1,"accounts":[{"type":"STEAM","secret":"GEZDGNBV"}]}""",
            """{"version":1,"accounts":[{"type":"TOTP","secret":"not base32!"}]}""",
            """{"version":1,"accounts":[{"type":"TOTP","secret":""}]}""",
            """{"version":1,"accounts":[{"type":"TOTP","secret":"GEZDGNBV","digits":12}]}""",
            """{"version":1,"accounts":[{"type":"TOTP"}]}""",
        )
        for (text in bad) {
            val e = assertFailsWith<BackupException>(text) { BackupPayload.decode(text.toByteArray()) }
            assertEquals(BackupException.Reason.UNSUPPORTED, e.reason)
        }
    }

    @Test
    fun errorsNeverQuoteThePayload() {
        val e = assertFailsWith<BackupException> {
            BackupPayload.decode("""{"version":1,"accounts":[{"type":"TOTP","secret":"JBSWY3DPEHPK3PXP","digits":"x"}]}""".toByteArray())
        }
        assertTrue("JBSWY3DPEHPK3PXP" !in e.message.orEmpty())
        assertEquals(null, e.cause)
    }
}
