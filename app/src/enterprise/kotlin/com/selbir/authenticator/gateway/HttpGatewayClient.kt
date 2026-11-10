package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.VeiloChallenge
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain `HttpURLConnection`: no cookies, no redirects, system trust store. Certificate pinning
 * per tenant is a 0.6 item on the Gateway roadmap and will arrive through the QR.
 */
class HttpGatewayClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val versionName: String = "",
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : GatewayClient {
    private class Answer(val status: Int, val body: String)

    private fun request(url: String, method: String, body: ByteArray?, headers: Map<String, String>): Answer {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
            val a = request(enrolment.gatewayUrl + "/enrol/confirm", "POST", body, mapOf("User-Agent" to "VeiloAuthenticator/${app.versionName}"))
            GatewayJson.confirmResult(a.status, a.body)
        } catch (e: IOException) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        } catch (e: Exception) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        }
    }

    override fun pendingChallenges(gatewayUrl: String, deviceId: String, signer: DeviceSigner, state: DeviceState?): ChallengeFetch {
        val path = "/device/challenges"
        return try {
            val headers = deviceAuth(deviceId, "GET", path, signer) +
                (state?.let { mapOf(GatewayJson.DEVICE_STATE_HEADER to GatewayJson.deviceStateHeader(it)) } ?: emptyMap())
            val a = request(gatewayUrl + path, "GET", null, headers)
            GatewayJson.challengeFetch(a.status, a.body)
        } catch (e: Exception) {
            ChallengeFetch.Unreachable(e.javaClass.simpleName)
        }
    }

    override fun answer(gatewayUrl: String, deviceId: String, challenge: PendingChallenge, number: String, signer: DeviceSigner): AnswerResult {
        val path = "/device/challenges/${challenge.id}/response"
        return try {
            val signature = signer.sign(VeiloChallenge.responsePayload(challenge.nonce, challenge.rpId, number, deviceId))
            val body = GatewayJson.answerRequest(number, signature).toByteArray()
            val a = request(gatewayUrl + path, "POST", body, deviceAuth(deviceId, "POST", path, signer))
            GatewayJson.answerResult(a.status, a.body)
        } catch (e: Exception) {
            AnswerResult.Unreachable(e.javaClass.simpleName)
        }
    }
}
