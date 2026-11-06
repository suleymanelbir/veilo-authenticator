package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OtpTest {
    private val sha1Secret = "12345678901234567890".toByteArray()
    private val sha256Secret = "12345678901234567890123456789012".toByteArray()
    private val sha512Secret = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    /** RFC 4226, Appendix D. */
    @Test
    fun hotpMatchesRfc4226Vectors() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        val hmac = Otp.softwareHmac(sha1Secret, OtpAlgorithm.SHA1)
        expected.forEachIndexed { counter, code ->
            assertEquals(code, Otp.hotp(hmac, counter.toLong(), 6), "counter=$counter")
        }
    }

    /** RFC 6238, Appendix B. */
    @Test
    fun totpMatchesRfc6238Vectors() {
        val vectors = listOf(
            Triple(59L, OtpAlgorithm.SHA1, "94287082"),
            Triple(59L, OtpAlgorithm.SHA256, "46119246"),
            Triple(59L, OtpAlgorithm.SHA512, "90693936"),
            Triple(1111111109L, OtpAlgorithm.SHA1, "07081804"),
            Triple(1111111109L, OtpAlgorithm.SHA256, "68084774"),
            Triple(1111111109L, OtpAlgorithm.SHA512, "25091201"),
            Triple(1111111111L, OtpAlgorithm.SHA1, "14050471"),
            Triple(1111111111L, OtpAlgorithm.SHA256, "67062674"),
            Triple(1111111111L, OtpAlgorithm.SHA512, "99943326"),
            Triple(1234567890L, OtpAlgorithm.SHA1, "89005924"),
            Triple(1234567890L, OtpAlgorithm.SHA256, "91819424"),
            Triple(1234567890L, OtpAlgorithm.SHA512, "93441116"),
            Triple(2000000000L, OtpAlgorithm.SHA1, "69279037"),
            Triple(2000000000L, OtpAlgorithm.SHA256, "90698825"),
            Triple(2000000000L, OtpAlgorithm.SHA512, "38618901"),
            Triple(20000000000L, OtpAlgorithm.SHA1, "65353130"),
            Triple(20000000000L, OtpAlgorithm.SHA256, "77737706"),
            Triple(20000000000L, OtpAlgorithm.SHA512, "47863826"),
        )
        for ((time, algorithm, code) in vectors) {
            val secret = when (algorithm) {
                OtpAlgorithm.SHA1 -> sha1Secret
                OtpAlgorithm.SHA256 -> sha256Secret
                OtpAlgorithm.SHA512 -> sha512Secret
            }
            val hmac = Otp.softwareHmac(secret, algorithm)
            assertEquals(code, Otp.totp(hmac, time, period = 30, digits = 8), "t=$time $algorithm")
        }
    }

    @Test
    fun secondsRemainingCoversWholePeriod() {
        assertEquals(30, Otp.secondsRemaining(0, 30))
        assertEquals(1, Otp.secondsRemaining(29, 30))
        assertEquals(30, Otp.secondsRemaining(30, 30))
        assertEquals(1, Otp.secondsRemaining(1111111109, 30))
    }

    @Test
    fun rejectsUnsupportedDigitCounts() {
        val hmac = Otp.softwareHmac(sha1Secret, OtpAlgorithm.SHA1)
        assertFailsWith<IllegalArgumentException> { Otp.hotp(hmac, 0, 5) }
        assertFailsWith<IllegalArgumentException> { Otp.hotp(hmac, 0, 9) }
    }
}
