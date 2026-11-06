package com.selbir.authenticator.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class OtpType { TOTP, HOTP }

enum class OtpAlgorithm(val jcaName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
}

/**
 * Computes an HMAC with a key the caller never sees.
 *
 * OTP generation is written against this instead of a raw secret so the key can
 * live inside secure hardware (Android Keystore) and never enter app memory.
 */
fun interface HmacFunction {
    fun compute(message: ByteArray): ByteArray
}

/** HOTP (RFC 4226) and TOTP (RFC 6238). */
object Otp {
    const val MIN_DIGITS = 6
    const val MAX_DIGITS = 8

    private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

    fun hotp(hmac: HmacFunction, counter: Long, digits: Int): String {
        require(digits in MIN_DIGITS..MAX_DIGITS) { "Unsupported digit count: $digits" }
        val hash = hmac.compute(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % POW10[digits]).toString().padStart(digits, '0')
    }

    fun totp(hmac: HmacFunction, epochSeconds: Long, period: Int, digits: Int): String =
        hotp(hmac, timeCounter(epochSeconds, period), digits)

    /** The TOTP moving factor: number of whole [period]s since the Unix epoch. */
    fun timeCounter(epochSeconds: Long, period: Int): Long {
        require(period > 0) { "Period must be positive" }
        return Math.floorDiv(epochSeconds, period.toLong())
    }

    /** Seconds until the current TOTP code expires, in `1..period`. */
    fun secondsRemaining(epochSeconds: Long, period: Int): Int =
        period - Math.floorMod(epochSeconds, period.toLong()).toInt()

    /** An [HmacFunction] over a secret held in app memory. The caller owns (and should wipe) [secret]. */
    fun softwareHmac(secret: ByteArray, algorithm: OtpAlgorithm): HmacFunction = HmacFunction { message ->
        val mac = Mac.getInstance(algorithm.jcaName)
        mac.init(SecretKeySpec(secret, algorithm.jcaName))
        mac.doFinal(message)
    }
}
