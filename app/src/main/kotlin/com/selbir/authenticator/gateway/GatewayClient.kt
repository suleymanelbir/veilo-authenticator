package com.selbir.authenticator.gateway

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
}
