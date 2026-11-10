package com.selbir.authenticator.ui

import androidx.annotation.StringRes
import com.selbir.authenticator.R

/** Maps a Gateway `/enrol/confirm` reason code (PROTOCOL.md §4, §4.1) to the message the user sees. */
object GatewayRefusal {
    @StringRes
    fun messageRes(code: String): Int = when (code) {
        "key_level_below_policy" -> R.string.enrol_gateway_refused_key_level
        "ticket_expired", "ticket_already_used", "unknown_ticket" -> R.string.enrol_gateway_refused_ticket
        "unknown_app", "attestation_app_mismatch" -> R.string.enrol_gateway_refused_app
        "attestation_missing", "attestation_invalid", "challenge_mismatch", "device_key_mismatch" ->
            R.string.enrol_gateway_refused_attestation
        "attestation_revoked" -> R.string.enrol_gateway_refused_revoked
        "device_not_trusted" -> R.string.enrol_gateway_refused_device
        else -> R.string.enrol_gateway_refused
    }
}
