package com.selbir.authenticator.gateway

import android.net.http.X509TrustManagerExtensions
import com.selbir.authenticator.core.TlsPins
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Gateway D-18: runs inside the TLS handshake, before any HTTP byte (headers with the device
 * signature, the enrolment body) is sent. Accepts only when (1) the platform's own hostname
 * check passes, and (2) the chain the *system trust store* validated — not the chain the
 * server chose to send, which an attacker controls — contains a certificate whose key is in
 * [pins]. The system validation already happened in the handshake; this adds a narrower
 * requirement on top and never widens trust.
 */
class PinnedHostnameVerifier(
    private val pins: List<String>,
    private val onMismatch: () -> Unit = {},
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) return false
        val ok = try {
            val presented = session.peerCertificates.map { it as X509Certificate }.toTypedArray()
            // "RSA" is what Android's own pinning callers pass; Conscrypt does not narrow on it.
            val validated = X509TrustManagerExtensions(systemTrustManager()).checkServerTrusted(presented, "RSA", hostname)
            TlsPins.matches(pins, validated.map { it.publicKey.encoded })
        } catch (e: Exception) {
            false
        }
        if (!ok) onMismatch()
        return ok
    }

    private companion object {
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
