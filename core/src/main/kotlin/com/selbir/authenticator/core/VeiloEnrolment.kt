package com.selbir.authenticator.core

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Where a Keystore key must live for a Gateway enrolment; ordered weakest to strongest. */
enum class VeiloKeyLevel(val wire: String) {
    SOFTWARE("software"),
    TEE("tee"),
    STRONGBOX("strongbox");

    fun satisfies(required: VeiloKeyLevel): Boolean = ordinal >= required.ordinal

    companion object {
        fun fromWire(value: String?): VeiloKeyLevel? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/**
 * The Veilo Gateway extension of an enrolment QR (Gateway PROTOCOL.md §4): the Gateway to
 * confirm with, a single-use ticket, and the key level the organisation requires. The public
 * edition ignores all of it and enrols a plain TOTP account; the Enterprise flavour confirms
 * the enrolment with the Gateway after the secret is in the Keystore.
 */
data class VeiloEnrolment(
    val gatewayUrl: String,
    val ticket: String,
    val requiredLevel: VeiloKeyLevel,
) {
    /** The challenge the device key's attestation must carry: SHA-256("veilo-enrol" || ticket). */
    fun attestationChallenge(): ByteArray = attestationChallenge(ticket)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val PARAM_GATEWAY = "veilo_gw"
        const val PARAM_TICKET = "veilo_ticket"
        const val PARAM_KEY_LEVEL = "veilo_keylevel"

        /** 32 random bytes as URL-safe Base64 without padding. */
        private val TICKET_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

        /**
         * Reads the extension from an otpauth URI. Null when the URI carries no Veilo parameters.
         * Throws when it carries some but they are unusable: a half-present extension is a
         * broken QR, not a plain one.
         *
         * @param allowPlainHttp lab builds only; production Gateways speak HTTPS.
         */
        fun parse(otpauthUri: String, allowPlainHttp: Boolean = false): VeiloEnrolment? {
            val query = otpauthUri.trim().substringAfter('?', "").substringBefore('#')
            val params = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                decode(pair.substringBefore('=')).lowercase() to decode(pair.substringAfter('=', ""))
            }
            val gateway = params[PARAM_GATEWAY]
            val ticket = params[PARAM_TICKET]
            val level = params[PARAM_KEY_LEVEL]
            if (gateway == null && ticket == null && level == null) return null
            requireNotNull(gateway) { "Missing $PARAM_GATEWAY" }
            requireNotNull(ticket) { "Missing $PARAM_TICKET" }
            require(TICKET_FORMAT.matches(ticket)) { "Bad ticket" }
            val required = requireNotNull(VeiloKeyLevel.fromWire(level)) { "Bad key level" }
            val uri = try {
                URI(gateway)
            } catch (e: Exception) {
                throw IllegalArgumentException("Bad Gateway URL", e)
            }
            require(uri.scheme == "https" || (allowPlainHttp && uri.scheme == "http")) { "Gateway must use https" }
            require(!uri.host.isNullOrEmpty()) { "Bad Gateway URL" }
            require(uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) { "Bad Gateway URL" }
            return VeiloEnrolment(gateway.trimEnd('/'), ticket, required)
        }

        fun attestationChallenge(ticket: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("veilo-enrol".toByteArray() + ticket.toByteArray())

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}
