package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel

/** What the app says about itself when it confirms an enrolment. */
data class AppIdentity(val packageName: String, val versionName: String, val signingDigest: String)

/** The device key the Enterprise flavour generates for one enrolment (Gateway PROTOCOL.md §4 step 3). */
data class DeviceKeyMaterial(
    /** EC P-256 public key as a JWK: kty, crv, x, y. */
    val publicJwk: Map<String, String>,
    /** The Keystore attestation chain, leaf first, PEM encoded. Empty when the device gave none. */
    val attestationChainPem: List<String>,
)

sealed class ConfirmResult {
    data class Recorded(val deviceId: String, val policyCompliant: Boolean, val deviations: List<String>) : ConfirmResult()

    /** The Gateway refused; the token is now disabled there. [code] is the protocol reason code. */
    data class Refused(val httpStatus: Int, val code: String) : ConfirmResult()

    /** No usable answer: offline, TLS failure, timeout, unexpected body. The ticket may still be valid. */
    data class Unreachable(val detail: String) : ConfirmResult()
}

/** [ConfirmResult.Unreachable.detail] and friends when the Gateway's TLS chain matched none of the QR's pins (D-18). */
const val TLS_PIN_MISMATCH = "tls_pin_mismatch"

/**
 * What the phone declares about itself on each challenge poll (Gateway PROTOCOL.md §5.1). Declared,
 * not attested: the Gateway shows it next to the attested facts, never instead of them.
 */
data class DeviceState(
    val model: String,
    val appVersion: String,
    /** YYYYMM of the Android security patch level, null when unknown. */
    val patchLevel: Int?,
    val managed: Boolean,
    /** Lower-case names of [com.selbir.authenticator.security.DeviceRisk] found at the last unlock. */
    val risks: List<String>,
)

/** Signs with the enrolment's device key (ECDSA P-256, SHA-256); the key never leaves the Keystore. */
fun interface DeviceSigner {
    fun sign(payload: ByteArray): ByteArray
}

sealed class ChallengeFetch {
    data class Pending(val challenges: List<PendingChallenge>) : ChallengeFetch()

    /** The Gateway rejected the device signature or no longer knows the device (§5 error codes). */
    data class Rejected(val httpStatus: Int, val code: String) : ChallengeFetch()

    data class Unreachable(val detail: String) : ChallengeFetch()
}

sealed class AnswerResult {
    data object Answered : AnswerResult()

    /** The Gateway finished the challenge as refused: `wrong_number`, `bad_signature`, `expired`. */
    data class Refused(val reason: String) : AnswerResult()

    /** The challenge is no longer pending (409 `challenge_<status>`), unknown (404) or the device was rejected (401/403). */
    data class Gone(val httpStatus: Int, val code: String) : AnswerResult()

    data class Unreachable(val detail: String) : AnswerResult()
}

/**
 * The Veilo Gateway as the app sees it. The public edition has no implementation and never
 * calls it; see `edition/Edition.kt` in each source set.
 */
interface GatewayClient {
    fun confirm(
        enrolment: VeiloEnrolment,
        keyLevel: VeiloKeyLevel,
        app: AppIdentity,
        deviceKey: DeviceKeyMaterial,
        policySnapshot: Map<String, Any?>?,
    ): ConfirmResult

    /** `GET /device/challenges` (PROTOCOL.md §5): what the Gateway is waiting on from this device; [state] rides along as the heartbeat (§5.1). */
    fun pendingChallenges(gatewayUrl: String, pins: List<String>, deviceId: String, signer: DeviceSigner, state: DeviceState? = null): ChallengeFetch

    /** `POST /device/challenges/{id}/response`: one attempt with the number the user chose. */
    fun answer(gatewayUrl: String, pins: List<String>, deviceId: String, challenge: PendingChallenge, number: String, signer: DeviceSigner): AnswerResult

    /**
     * `PUT /device/push` (PROTOCOL.md §5.2): how the Gateway wakes this device when a sign-in
     * waits for it. Returns the HTTP status (200 stored; 422 the Gateway cannot send that
     * kind; 0 unreachable). The wake-up itself carries nothing; the app then polls as usual.
     */
    fun registerPush(gatewayUrl: String, pins: List<String>, deviceId: String, signer: DeviceSigner, kind: String, token: String): Int
}
