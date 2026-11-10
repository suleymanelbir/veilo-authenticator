package com.selbir.authenticator.ui

import com.selbir.authenticator.R
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayRefusalTest {
    @Test
    fun everyProtocolCodeHasASpecificMessage() {
        val expected = mapOf(
            "key_level_below_policy" to R.string.enrol_gateway_refused_key_level,
            "ticket_expired" to R.string.enrol_gateway_refused_ticket,
            "ticket_already_used" to R.string.enrol_gateway_refused_ticket,
            "unknown_ticket" to R.string.enrol_gateway_refused_ticket,
            "unknown_app" to R.string.enrol_gateway_refused_app,
            "attestation_app_mismatch" to R.string.enrol_gateway_refused_app,
            "attestation_missing" to R.string.enrol_gateway_refused_attestation,
            "attestation_invalid" to R.string.enrol_gateway_refused_attestation,
            "challenge_mismatch" to R.string.enrol_gateway_refused_attestation,
            "device_key_mismatch" to R.string.enrol_gateway_refused_attestation,
            "attestation_revoked" to R.string.enrol_gateway_refused_revoked,
            "device_not_trusted" to R.string.enrol_gateway_refused_device,
        )
        expected.forEach { (code, res) -> assertEquals(code, res, GatewayRefusal.messageRes(code)) }
        assertEquals(R.string.enrol_gateway_refused, GatewayRefusal.messageRes("something_new"))
    }
}
