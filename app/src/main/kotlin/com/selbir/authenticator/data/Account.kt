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
    /** [Protection.EXPORTABLE] only: Base64 of the Keystore-wrapped secret. */
    val wrappedSecret: String? = null,
)

/** On-disk format of the account list. */
@Serializable
data class Vault(
    val version: Int = 1,
    val accounts: List<Account> = emptyList(),
)
