package com.selbir.authenticator.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared vectors: `protocol-vectors.json` is a copy of the Gateway repository's
 * docs/protocol-vectors.json; the Gateway's tests load the same file.
 */
class VeiloEnrolmentTest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/protocol-vectors.json")) { "vectors missing" }.readText()
    ).jsonObject
    private val otpauth = vectors["otpauth"]!!.jsonObject
    private val uri = otpauth["uri"]!!.jsonPrimitive.content

    @Test
    fun parsesTheVectorUri() {
        val e = checkNotNull(VeiloEnrolment.parse(uri))
        assertEquals(otpauth["gateway_url"]!!.jsonPrimitive.content, e.gatewayUrl)
        assertEquals(otpauth["ticket"]!!.jsonPrimitive.content, e.ticket)
        assertEquals(VeiloKeyLevel.STRONGBOX, e.requiredLevel)
        assertEquals(1, VeiloEnrolment.PROTOCOL_VERSION)
        assertEquals(vectors["protocol"]!!.jsonPrimitive.content, "1")
    }

    @Test
    fun attestationChallengeMatchesTheVector() {
        val e = checkNotNull(VeiloEnrolment.parse(uri))
        val hex = e.attestationChallenge().joinToString("") { "%02x".format(it) }
        assertEquals(otpauth["attestation_challenge_hex"]!!.jsonPrimitive.content, hex)
    }

    @Test
    fun theOtpPartOfTheVectorStillParsesAsPlainTotp() {
        val spec = OtpAuthUri.parse(uri)
        assertEquals("Deneme AŞ", spec.issuer)
        assertEquals("ayse", spec.accountName)
        assertEquals(otpauth["seed_hex"]!!.jsonPrimitive.content, spec.secret.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun plainUriHasNoExtension() {
        assertNull(VeiloEnrolment.parse("otpauth://totp/Example:alice?secret=MZXW6YTBOI&issuer=Example"))
    }

    @Test
    fun keyLevelOrderMatchesTheVector() {
        val levels = vectors["key_levels"]!!.jsonObject
        for (l in VeiloKeyLevel.entries) assertEquals(levels[l.wire]!!.jsonPrimitive.content.toInt(), l.ordinal)
        assertTrue(VeiloKeyLevel.STRONGBOX.satisfies(VeiloKeyLevel.TEE))
        assertTrue(!VeiloKeyLevel.TEE.satisfies(VeiloKeyLevel.STRONGBOX))
        assertEquals(VeiloKeyLevel.TEE, VeiloKeyLevel.fromWire(" Tee "))
        assertNull(VeiloKeyLevel.fromWire("hsm"))
    }

    @Test
    fun halfPresentOrUnsafeExtensionsAreRejected() {
        val ticket = "A".repeat(43)
        assertFailsWith<IllegalArgumentException> { VeiloEnrolment.parse("otpauth://totp/a?secret=MZXW6YTBOI&veilo_ticket=$ticket") }
        assertFailsWith<IllegalArgumentException> {
            VeiloEnrolment.parse("otpauth://totp/a?secret=MZXW6YTBOI&veilo_gw=https%3A%2F%2Fgw&veilo_ticket=short&veilo_keylevel=tee")
        }
        assertFailsWith<IllegalArgumentException> {
            VeiloEnrolment.parse("otpauth://totp/a?secret=MZXW6YTBOI&veilo_gw=https%3A%2F%2Fgw&veilo_ticket=$ticket&veilo_keylevel=hsm")
        }
        assertFailsWith<IllegalArgumentException> {
            VeiloEnrolment.parse("otpauth://totp/a?secret=MZXW6YTBOI&veilo_gw=http%3A%2F%2Fgw.example&veilo_ticket=$ticket&veilo_keylevel=tee")
        }
        assertFailsWith<IllegalArgumentException> {
            VeiloEnrolment.parse("otpauth://totp/a?secret=MZXW6YTBOI&veilo_gw=https%3A%2F%2Fu%3Ap%40gw.example&veilo_ticket=$ticket&veilo_keylevel=tee")
        }
        // the lab build may talk to a plain-http Gateway
        val lab = VeiloEnrolment.parse(
            "otpauth://totp/a?secret=MZXW6YTBOI&veilo_gw=http%3A%2F%2F10.0.0.5%3A8282%2F&veilo_ticket=$ticket&veilo_keylevel=software",
            allowPlainHttp = true,
        )
        assertEquals("http://10.0.0.5:8282", lab!!.gatewayUrl)
    }
}
