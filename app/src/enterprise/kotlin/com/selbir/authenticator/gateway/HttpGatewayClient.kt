package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.VeiloChallenge
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Plain `HttpURLConnection`: no cookies, no redirects, system trust store, and — when the
 * enrolment QR carried `veilo_pins` (Gateway D-18) — a pin check in the TLS handshake
 * ([PinnedHostnameVerifier]); a mismatch is reported as [TLS_PIN_MISMATCH] and nothing is sent.
 */
class HttpGatewayClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val versionName: String = "",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : GatewayClient {
    private class Answer(val status: Int, val body: String)

    private class PinMismatch : IOException(TLS_PIN_MISMATCH)

    private fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>, pins: List<String>): Answer {
        var mismatch = false
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            if (pins.isNotEmpty()) {
                // a pinned Gateway is never contacted over plain http (lab builds allow http otherwise)
                val https = this as? HttpsURLConnection ?: throw PinMismatch()
                https.hostnameVerifier = PinnedHostnameVerifier(pins) { mismatch = true }
            }
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Veilo-Protocol", VeiloEnrolment.PROTOCOL_VERSION.toString())
            setRequestProperty("User-Agent", "VeiloAuthenticator/$versionName")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            try {
                connection.connect()
            } catch (e: IOException) {
                throw if (mismatch) PinMismatch() else e
            }
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return Answer(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    /** `Authorization: Veilo-Device ...` for this call (PROTOCOL.md §5): the path, not the full URL, is signed. */
    private fun deviceAuth(deviceId: String, method: String, path: String, signer: DeviceSigner): Map<String, String> {
        val ts = clock()
        val signature = signer.sign(VeiloChallenge.deviceAuthPayload(deviceId, ts, method, path))
        return mapOf("Authorization" to VeiloChallenge.deviceAuthHeader(deviceId, ts, signature))
    }

    override fun confirm(
        enrolment: VeiloEnrolment,
        keyLevel: VeiloKeyLevel,
        app: AppIdentity,
        deviceKey: DeviceKeyMaterial,
        policySnapshot: Map<String, Any?>?,
    ): ConfirmResult {
        val body = GatewayJson.confirmRequest(enrolment, keyLevel, app, deviceKey, policySnapshot).toByteArray()
        return try {
            val a = request(enrolment.gatewayUrl + "/enrol/confirm", "POST", body, mapOf("User-Agent" to "VeiloAuthenticator/${app.versionName}"), enrolment.pins)
            GatewayJson.confirmResult(a.status, a.body)
        } catch (e: PinMismatch) {
            ConfirmResult.Unreachable(TLS_PIN_MISMATCH)
        } catch (e: IOException) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        } catch (e: Exception) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        }
    }

    override fun pendingChallenges(gatewayUrl: String, pins: List<String>, deviceId: String, signer: DeviceSigner, state: DeviceState?): ChallengeFetch {
        val path = "/device/challenges"
        return try {
            val headers = deviceAuth(deviceId, "GET", path, signer) +
                (state?.let { mapOf(GatewayJson.DEVICE_STATE_HEADER to GatewayJson.deviceStateHeader(it)) } ?: emptyMap())
            val a = request(gatewayUrl + path, "GET", null, headers, pins)
            GatewayJson.challengeFetch(a.status, a.body)
        } catch (e: PinMismatch) {
            ChallengeFetch.Unreachable(TLS_PIN_MISMATCH)
        } catch (e: Exception) {
            ChallengeFetch.Unreachable(e.javaClass.simpleName)
        }
    }

    override fun answer(gatewayUrl: String, pins: List<String>, deviceId: String, challenge: PendingChallenge, number: String, signer: DeviceSigner): AnswerResult {
        val path = "/device/challenges/${challenge.id}/response"
        return try {
            val signature = signer.sign(VeiloChallenge.responsePayload(challenge.nonce, challenge.rpId, number, deviceId))
            val body = GatewayJson.answerRequest(number, signature).toByteArray()
            val a = request(gatewayUrl + path, "POST", body, deviceAuth(deviceId, "POST", path, signer), pins)
            GatewayJson.answerResult(a.status, a.body)
        } catch (e: PinMismatch) {
            AnswerResult.Unreachable(TLS_PIN_MISMATCH)
        } catch (e: Exception) {
            AnswerResult.Unreachable(e.javaClass.simpleName)
        }
    }

    override fun registerPush(gatewayUrl: String, pins: List<String>, deviceId: String, signer: DeviceSigner, kind: String, token: String): Int {
        val path = "/device/push"
        return try {
            val body = GatewayJson.pushRequest(kind, token).toByteArray()
            request(gatewayUrl + path, "PUT", body, deviceAuth(deviceId, "PUT", path, signer), pins).status
        } catch (e: Exception) {
            0
        }
    }
}
