package com.selbir.authenticator.core

import java.io.ByteArrayOutputStream

/** RFC 4648 Base32, the encoding used for OTP shared secrets. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Decodes [input] leniently: case-insensitive, ignoring spaces, dashes and padding,
     * because that is how services print secrets for manual entry.
     *
     * @throws IllegalArgumentException on any other character. The message never
     * contains the input, since it is secret material.
     */
    fun decode(input: String): ByteArray {
        val out = ByteArrayOutputStream(input.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in input) {
            if (c == ' ' || c == '-' || c == '=') continue
            val value = ALPHABET.indexOf(c.uppercaseChar())
            require(value >= 0) { "Invalid Base32 character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray, padding: Boolean = false): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        if (padding) while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }
}
