package com.selbir.authenticator.data

import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpType
import kotlinx.serialization.Serializable

/** How an account's OTP secret is stored. See `security/SecretStore`. */
enum class Protection {
    /** The secret is a non-exportable Keystore key. Cannot be backed up. */
    HARDWARE_BOUND,

    /** The secret is stored encrypted under a Keystore key and may be exported to a backup. */
    EXPORTABLE,
}

/** One enrolled account. Never holds a plaintext secret. */
@Serializable
data class Account(
    val id: String,
    val type: OtpType,
    val issuer: String,
    val name: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val period: Int,
    /** HOTP only: the counter the next code will be generated from. */
    val counter: Long = 0,
    val protection: Protection,
    /** User-defined group for organising accounts (e.g. "Work", "Personal"). Empty = ungrouped. */
    val group: String = "",
    /** Pinned accounts are shown at the top of the list. */
    val pinned: Boolean = false,
    /** [Protection.EXPORTABLE] only: Base64 of the Keystore-wrapped secret. */
    val wrappedSecret: String? = null,
    /** Enterprise flavour: the Gateway this account was confirmed with (null for a plain account). */
    val gateway: GatewayBinding? = null,
)

/**
 * Where an Enterprise account's organisation Gateway lives and what it calls this phone
 * (Gateway PROTOCOL.md §4 step 4, §5). The device key is in the Keystore under the account's
 * alias; from 0.4 it signs the Gateway's challenges.
 */
@Serializable
data class GatewayBinding(
    val url: String,
    val deviceId: String,
    /**
     * When the organisation revoked this device (Gateway 0.5, 401 `device_revoked`), epoch
     * milliseconds. A revoked binding is never polled again; its codes are refused by the
     * organisation's server, so the account card says so.
     */
    val revokedAt: Long? = null,
    /**
     * TLS pins from the enrolment QR (Gateway D-18): every connection to [url] must present a
     * system-trusted chain containing one of these keys. Empty = system trust only (Gateways
     * before 1.0, or an installation that pins nothing). Fixed at enrolment.
     */
    val pins: List<String> = emptyList(),
)

/** On-disk format of the account list. */
@Serializable
data class Vault(
    val version: Int = 1,
    val accounts: List<Account> = emptyList(),
)
