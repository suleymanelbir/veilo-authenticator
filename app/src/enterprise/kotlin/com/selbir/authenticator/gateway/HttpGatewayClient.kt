package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain `HttpURLConnection`: one POST, no cookies, no redirects, system trust store. Certificate
 * pinning per tenant is a 0.6 item on the Gateway roadmap and will arrive through the QR.
 */
class HttpGatewayClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : GatewayClient {
    override fun confirm(
        enrolment: VeiloEnrolment,
        keyLevel: VeiloKeyLevel,
        app: AppIdentity,
        deviceKey: DeviceKeyMaterial,
        policySnapshot: Map<String, Any?>?,
    ): ConfirmResult {
        val body = GatewayJson.confirmRequest(enrolment, keyLevel, app, deviceKey, policySnapshot).toByteArray()
        val connection = try {
            (URL(enrolment.gatewayUrl + "/enrol/confirm").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Veilo-Protocol", VeiloEnrolment.PROTOCOL_VERSION.toString())
                setRequestProperty("User-Agent", "VeiloAuthenticator/${app.versionName}")
            }
        } catch (e: Exception) {
            return ConfirmResult.Unreachable(e.javaClass.simpleName)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            GatewayJson.confirmResult(status, text)
        } catch (e: IOException) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}
