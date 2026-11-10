package com.selbir.authenticator.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.AlgorithmParameters
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * PROTOCOL.md §5 vectors (the `challenge` section). ECDSA is randomised, so the vector
 * signatures are verified with the vector public key rather than reproduced: that proves the
 * payload bytes and the JWK decoding, which is what the Gateway checks.
 */
class VeiloChallengeTest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/protocol-vectors.json")) { "vectors missing" }.readText()
    ).jsonObject["challenge"]!!.jsonObject

    private fun str(key: String) = vectors[key]!!.jsonPrimitive.content

    private val publicKey = run {
        val jwk = vectors["device_pubkey"]!!.jsonObject
        val x = BigInteger(1, VeiloChallenge.base64UrlDecode(jwk["x"]!!.jsonPrimitive.content))
        val y = BigInteger(1, VeiloChallenge.base64UrlDecode(jwk["y"]!!.jsonPrimitive.content))
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), params.getParameterSpec(ECParameterSpec::class.java))
        )
    }

    private fun verifies(payload: ByteArray, signatureB64: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload)
            verify(VeiloChallenge.base64UrlDecode(signatureB64))
        }

    @Test
    fun responsePayloadMatchesTheVectorAndItsSignatureVerifies() {
        val payload = VeiloChallenge.responsePayload(str("nonce"), str("rp_id"), str("number"), str("device_id"))
        assertEquals(str("response_payload"), String(payload))
        assertTrue(verifies(payload, str("response_signature")))
        // the same signature over a different number must not verify
        val other = VeiloChallenge.responsePayload(str("nonce"), str("rp_id"), "17", str("device_id"))
        assertFalse(verifies(other, str("response_signature")))
    }

    @Test
    fun deviceAuthPayloadAndHeaderMatchTheVector() {
        val auth = vectors["device_auth"]!!.jsonObject
        val ts = auth["ts"]!!.jsonPrimitive.content.toLong()
        val payload = VeiloChallenge.deviceAuthPayload(
            str("device_id"), ts, auth["method"]!!.jsonPrimitive.content, auth["path"]!!.jsonPrimitive.content,
        )
        assertEquals(auth["payload"]!!.jsonPrimitive.content, String(payload))
        val signature = auth["signature"]!!.jsonPrimitive.content
        assertTrue(verifies(payload, signature))
        assertEquals(
            auth["header"]!!.jsonPrimitive.content,
            VeiloChallenge.deviceAuthHeader(str("device_id"), ts, VeiloChallenge.base64UrlDecode(signature)),
        )
    }

    @Test
    fun numbersAreTwoDigits() {
        assertTrue(vectors["choices_example"]!!.jsonArray.all { VeiloChallenge.NUMBER_FORMAT.matches(it.jsonPrimitive.content) })
        assertFalse(VeiloChallenge.NUMBER_FORMAT.matches("07"))
        assertFalse(VeiloChallenge.NUMBER_FORMAT.matches("100"))
        assertEquals(120, vectors["expiry_seconds"]!!.jsonPrimitive.content.toInt())
    }
}
