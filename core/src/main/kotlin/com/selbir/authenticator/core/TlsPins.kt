package com.selbir.authenticator.core

import java.security.MessageDigest
import java.util.Base64

/**
 * TLS pins from the enrolment QR (Gateway D-18, `veilo_pins`): `sha256/<Base64 of the SHA-256 of a
 * certificate's SubjectPublicKeyInfo>`. The phone accepts a Gateway connection only when the
 * system already trusts the chain *and* one certificate of the validated chain has a pinned key.
 * Pins name CAs (roots), not the server's own certificate, so renewals do not lock phones out.
 */
object TlsPins {
    const val PREFIX = "sha256/"

    /** Comma-separated pins; throws on any malformed entry (a broken pin would lock the account out). */
    fun parse(value: String): List<String> {
        val pins = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        pins.forEach { require(isPin(it)) { "Bad pin" } }
        return pins.distinct()
    }

    fun isPin(pin: String): Boolean {
        if (!pin.startsWith(PREFIX)) return false
        val digest = try {
            Base64.getDecoder().decode(pin.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            return false
        }
        return digest.size == 32
    }

    /** The pin of one public key, given as its DER SubjectPublicKeyInfo (`PublicKey.getEncoded()`). */
    fun pinOf(spkiDer: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))

    /** True when [pins] is empty (nothing pinned) or one key of the validated chain is pinned. */
    fun matches(pins: List<String>, chainSpkiDer: List<ByteArray>): Boolean {
        if (pins.isEmpty()) return true
        val wanted = pins.toSet()
        return chainSpkiDer.any { pinOf(it) in wanted }
    }
}
