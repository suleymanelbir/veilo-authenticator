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

    /** `GET /device/challenges` (PROTOCOL.md §5): what the Gateway is waiting on from this device. */
    fun pendingChallenges(gatewayUrl: String, deviceId: String, signer: DeviceSigner): ChallengeFetch

    /** `POST /device/challenges/{id}/response`: one attempt with the number the user chose. */
    fun answer(gatewayUrl: String, deviceId: String, challenge: PendingChallenge, number: String, signer: DeviceSigner): AnswerResult
}
