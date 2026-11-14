package com.selbir.authenticator.core

import java.util.Base64

/**
 * Signed challenge-response, Gateway PROTOCOL.md §5 (0.4). Pure byte formats and encodings so
 * the vectors in protocol-vectors.json can be checked without a Keystore; the signing itself
 * (ECDSA P-256 / SHA-256 with the enrolment's device key) is the Enterprise flavour's job.
 */
object VeiloChallenge {
    const val AUTH_SCHEME = "Veilo-Device"

    /** Seconds the Gateway tolerates between the phone's clock and its own for [deviceAuthHeader]. */
    const val AUTH_WINDOW_SECONDS = 60L

    /** The display number is two digits, "10".."99". */
    val NUMBER_FORMAT = Regex("^[1-9][0-9]$")

    /** What the phone signs when it answers: `nonce|rp_id|number|device_id`. */
    fun responsePayload(nonce: String, rpId: String, number: String, deviceId: String): ByteArray =
        "$nonce|$rpId|$number|$deviceId".toByteArray()

    /** What the phone signs to authenticate a request: `veilo-device|device_id|ts|METHOD|path`. */
    fun deviceAuthPayload(deviceId: String, ts: Long, method: String, path: String): ByteArray =
        "veilo-device|$deviceId|$ts|${method.uppercase()}|$path".toByteArray()

    /** `Authorization: Veilo-Device <device_id>.<ts>.<signature>` with the signature URL-safe Base64, no padding. */
    fun deviceAuthHeader(deviceId: String, ts: Long, signature: ByteArray): String =
        "$AUTH_SCHEME $deviceId.$ts.${base64Url(signature)}"

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/** One pending challenge as `GET /device/challenges` returns it to the phone. */
data class PendingChallenge(
    val id: String,
    val rpId: String,
    val nonce: String,
    /** Three candidate numbers, one of them the one on the login screen; for [APPROVAL_CONFIRM] only that one. */
    val choices: List<String>,
    /** ISO-8601 with offset, as the Gateway sends it. */
    val expiresAt: String,
    /**
     * [APPROVAL_NUMBER] (number matching) or [APPROVAL_CONFIRM] (Gateway 1.1, PROTOCOL.md §5.3): the
     * relying party cannot show a number (an LDAP application), so the phone asks Onayla / Reddet
     * and signs the single choice exactly like a chosen number.
     */
    val approval: String = APPROVAL_NUMBER,
    /**
     * What started it (Gateway 1.1): [ORIGIN_LDAP] (the directory already accepted the password),
     * [ORIGIN_QR] (this phone scanned a sign-in QR) or anything else. Only chooses the wording.
     */
    val origin: String = "",
) {
    val isConfirm: Boolean get() = approval == APPROVAL_CONFIRM
    val fromQr: Boolean get() = origin == ORIGIN_QR

    companion object {
        const val APPROVAL_NUMBER = "number"
        const val APPROVAL_CONFIRM = "confirm"
        const val ORIGIN_LDAP = "ldap"
        const val ORIGIN_QR = "qr"
    }
}
