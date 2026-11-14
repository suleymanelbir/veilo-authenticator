package com.selbir.authenticator.core

import java.net.URI

/**
 * A "Veilo ile giriş" QR (Gateway 1.1, PROTOCOL.md §6): `veilo-login:1:<login id>:<secret>:<Gateway URL>`.
 * The browser of an application that signs in through the Gateway shows it; the secret changes
 * every 30 seconds. The phone claims the sign-in with `POST /device/qr-login` and then gets an
 * ordinary Onayla / Reddet request naming the application and the browser's address.
 *
 * The URL inside the QR is never trusted on its own: the app sends the claim only to a Gateway
 * it already enrolled with (same URL, that enrolment's TLS pins and device key). A QR naming
 * any other address is refused before anything leaves the phone.
 */
data class VeiloQrLogin(val gatewayUrl: String, val loginId: String, val secret: String) {
    companion object {
        const val PREFIX = "veilo-login:"
        private const val VERSION = "1"
        private val LOGIN_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val SECRET = Regex("^[A-Za-z0-9_-]{43}$")

        fun looksLikeOne(text: String): Boolean = text.trim().startsWith(PREFIX, ignoreCase = true)

        /** Throws [IllegalArgumentException] for anything but a well-formed version 1 QR. */
        fun parse(text: String, allowPlainHttp: Boolean = false): VeiloQrLogin {
            val trimmed = text.trim()
            require(trimmed.length <= 600 && looksLikeOne(trimmed)) { "Not a sign-in QR" }
            // the URL comes last because it contains ':' itself
            val parts = trimmed.substring(PREFIX.length).split(':', limit = 4)
            require(parts.size == 4) { "Bad sign-in QR" }
            val (version, loginId, secret, url) = parts
            require(version == VERSION) { "Unknown sign-in QR version" }
            require(LOGIN_ID.matches(loginId)) { "Bad login id" }
            require(SECRET.matches(secret)) { "Bad secret" }
            val uri = try {
                URI(url)
            } catch (e: Exception) {
                throw IllegalArgumentException("Bad Gateway URL", e)
            }
            require(uri.scheme == "https" || (allowPlainHttp && uri.scheme == "http")) { "Gateway must use https" }
            require(!uri.host.isNullOrEmpty()) { "Bad Gateway URL" }
            require(uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) { "Bad Gateway URL" }
            return VeiloQrLogin(url.trimEnd('/'), loginId, secret)
        }

        /** Whether [bindingUrl] (an enrolment's Gateway) is the Gateway this QR names: scheme, host and port compared case-insensitively, path exactly. */
        fun sameGateway(bindingUrl: String, qrUrl: String): Boolean = try {
            val a = URI(bindingUrl.trimEnd('/'))
            val b = URI(qrUrl.trimEnd('/'))
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b) &&
                a.rawPath.orEmpty() == b.rawPath.orEmpty()
        } catch (e: Exception) {
            false
        }

        private fun effectivePort(u: URI): Int = when {
            u.port != -1 -> u.port
            u.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
    }
}
