package com.selbir.authenticator.core

import java.net.URLDecoder
import java.nio.ByteBuffer

/**
 * Parses Google Authenticator (and Microsoft Authenticator) export QR codes.
 *
 * These encode `otpauth-migration://offline?data=BASE64` where the Base64 payload
 * is a Protocol Buffer message containing all accounts. This parser includes a
 * minimal protobuf wire-format decoder — no external protobuf library needed.
 *
 * Proto schema (from Google's migration format):
 * ```
 * message MigrationPayload { repeated OtpParameters otp_parameters = 1; }
 * message OtpParameters {
 *   bytes  secret    = 1;
 *   string name      = 2;
 *   string issuer    = 3;
 *   int32  algorithm = 4;  // 1=SHA1, 2=SHA256, 3=SHA512
 *   int32  digits    = 5;  // 1=SIX, 2=EIGHT
 *   int32  type      = 6;  // 1=HOTP, 2=TOTP
 *   int64  counter   = 7;
 * }
 * ```
 */
object GoogleAuthMigration {

    private const val PREFIX = "otpauth-migration://offline"

    /** @throws IllegalArgumentException if [uri] is not a valid migration URI. */
    fun parse(uri: String): List<OtpSpec> {
        val trimmed = uri.trim()
        require(trimmed.startsWith(PREFIX, ignoreCase = true)) { "Not an otpauth-migration URI" }

        val query = trimmed.substringAfter('?', "")
        val dataParam = query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0].equals("data", ignoreCase = true) }
            ?.getOrNull(1)
            ?: throw IllegalArgumentException("Missing data parameter")

        val decoded = URLDecoder.decode(dataParam, "UTF-8")
        val bytes = try {
            java.util.Base64.getDecoder().decode(decoded)
        } catch (_: Exception) {
            // Try URL-safe Base64
            java.util.Base64.getUrlDecoder().decode(decoded)
        }

        return parseMigrationPayload(bytes)
    }

    /** Checks whether [text] looks like a Google/Microsoft Authenticator migration QR. */
    fun isMigrationUri(text: String): Boolean =
        text.trimStart().startsWith(PREFIX, ignoreCase = true)

    private fun parseMigrationPayload(data: ByteArray): List<OtpSpec> {
        val reader = ProtoReader(ByteBuffer.wrap(data))
        val accounts = mutableListOf<OtpSpec>()
        while (reader.hasRemaining()) {
            val (fieldNumber, wireType) = reader.readTag()
            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val embedded = reader.readBytes()
                try {
                    accounts += parseOtpParameters(embedded)
                } catch (_: Exception) {
                    // Skip malformed entries
                }
            } else {
                reader.skip(wireType)
            }
        }
        require(accounts.isNotEmpty()) { "No accounts found in migration data" }
        return accounts
    }

    private fun parseOtpParameters(data: ByteArray): OtpSpec {
        val reader = ProtoReader(ByteBuffer.wrap(data))
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithmInt = 0
        var digitsInt = 0
        var typeInt = 0
        var counter = 0L

        while (reader.hasRemaining()) {
            val (fieldNumber, wireType) = reader.readTag()
            when (fieldNumber) {
                1 -> secret = reader.readBytes()
                2 -> name = reader.readString()
                3 -> issuer = reader.readString()
                4 -> algorithmInt = reader.readVarint().toInt()
                5 -> digitsInt = reader.readVarint().toInt()
                6 -> typeInt = reader.readVarint().toInt()
                7 -> counter = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }

        require(secret.isNotEmpty()) { "Empty secret" }

        // Extract issuer from "Issuer:account" name format if issuer field is empty
        if (issuer.isBlank() && ':' in name) {
            issuer = name.substringBefore(':').trim()
        }
        val accountName = if (':' in name) name.substringAfter(':').trim() else name.trim()

        return OtpSpec(
            type = when (typeInt) {
                1 -> OtpType.HOTP
                else -> OtpType.TOTP  // 0 (unspecified) and 2 both map to TOTP
            },
            issuer = issuer,
            accountName = accountName,
            secret = secret,
            algorithm = when (algorithmInt) {
                2 -> OtpAlgorithm.SHA256
                3 -> OtpAlgorithm.SHA512
                else -> OtpAlgorithm.SHA1  // 0 (unspecified) and 1
            },
            digits = when (digitsInt) {
                2 -> 8
                else -> 6  // 0 (unspecified) and 1
            },
            period = 30,
            counter = counter,
        )
    }

    // --- Minimal protobuf wire-format decoder ---

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_32BIT = 5

    private class ProtoReader(private val buf: ByteBuffer) {

        fun hasRemaining(): Boolean = buf.hasRemaining()

        fun readTag(): Pair<Int, Int> {
            val raw = readVarint().toInt()
            return (raw ushr 3) to (raw and 0x07)
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(buf.hasRemaining()) { "Truncated varint" }
                val b = buf.get().toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0L) break
                shift += 7
                require(shift < 64) { "Varint too long" }
            }
            return result
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && length <= buf.remaining()) { "Bad length: $length" }
            val bytes = ByteArray(length)
            buf.get(bytes)
            return bytes
        }

        fun readString(): String = readBytes().decodeToString()

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_64BIT -> {
                    require(buf.remaining() >= 8) { "Truncated 64-bit" }
                    buf.position(buf.position() + 8)
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint().toInt()
                    require(length >= 0 && length <= buf.remaining()) { "Bad length" }
                    buf.position(buf.position() + length)
                }
                WIRE_32BIT -> {
                    require(buf.remaining() >= 4) { "Truncated 32-bit" }
                    buf.position(buf.position() + 4)
                }
                else -> throw IllegalArgumentException("Unknown wire type: $wireType")
            }
        }
    }
}
