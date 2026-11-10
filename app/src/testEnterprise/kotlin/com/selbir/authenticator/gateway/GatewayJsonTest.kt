package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GatewayJsonTest {
    private val enrolment = VeiloEnrolment("https://gw.example.com", "A".repeat(43), VeiloKeyLevel.STRONGBOX)
    private val app = AppIdentity("tr.veilo.authenticator.enterprise", "0.5.0", "ab")
    private val key = DeviceKeyMaterial(mapOf("kty" to "EC", "crv" to "P-256", "x" to "x", "y" to "y"), listOf("-----BEGIN CERTIFICATE-----\nAA\n-----END CERTIFICATE-----"))

    @Test
    fun requestMatchesTheProtocolShape() {
        val text = GatewayJson.confirmRequest(enrolment, VeiloKeyLevel.TEE, app, key, mapOf("allow_pdf_export" to false, "organization_name" to "Deneme AŞ"))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("A".repeat(43), obj["ticket"]!!.jsonPrimitive.content)
        assertEquals("tee", obj["key_level"]!!.jsonPrimitive.content)
        assertEquals("tr.veilo.authenticator.enterprise", obj["app"]!!.jsonObject["package"]!!.jsonPrimitive.content)
        assertEquals("EC", obj["device_pubkey"]!!.jsonObject["kty"]!!.jsonPrimitive.content)
        assertEquals(1, obj["attestation_chain"]!!.jsonArray.size)
        assertEquals("false", obj["policy_snapshot"]!!.jsonObject["allow_pdf_export"]!!.jsonPrimitive.content)
        assertEquals("Deneme AŞ", obj["policy_snapshot"]!!.jsonObject["organization_name"]!!.jsonPrimitive.content)
        assertEquals("null", GatewayJson.confirmRequest(enrolment, VeiloKeyLevel.TEE, app, key, null).let {
            Json.parseToJsonElement(it).jsonObject["policy_snapshot"].toString()
        })
    }

    @Test
    fun responsesMapToResults() {
        val ok = GatewayJson.confirmResult(200, """{"status":"recorded","device_id":"d1","policy_compliant":false,"policy_deviations":["allow_pdf_export"]}""")
        assertIs<ConfirmResult.Recorded>(ok)
        assertEquals("d1", ok.deviceId)
        assertTrue(!ok.policyCompliant && ok.deviations == listOf("allow_pdf_export"))

        val refused = GatewayJson.confirmResult(409, """{"detail":{"code":"key_level_below_policy"}}""")
        assertEquals(ConfirmResult.Refused(409, "key_level_below_policy"), refused)
        assertEquals(ConfirmResult.Refused(410, "ticket_expired"), GatewayJson.confirmResult(410, """{"detail":{"code":"ticket_expired"}}"""))
        assertEquals(ConfirmResult.Refused(422, "http_422"), GatewayJson.confirmResult(422, """{"detail":[{"msg":"x"}]}"""))

        assertIs<ConfirmResult.Unreachable>(GatewayJson.confirmResult(502, "<html>bad gateway</html>"))
        assertIs<ConfirmResult.Unreachable>(GatewayJson.confirmResult(200, """{"status":"weird"}"""))
    }

    @Test
    fun pendingChallengesAreParsedAndMalformedOnesSkipped() {
        val body = """[
            {"id":"c1","rp_id":"vpn.deneme.example","status":"pending","expires_at":"2026-11-10T12:00:00+00:00","nonce":"AAEC","choices":["17","42","88"]},
            {"id":"c2","rp_id":"x","nonce":"AAEC","choices":["17","42"]},
            {"id":"c3","rp_id":"x","nonce":"AAEC","choices":["17","42","100"]},
            {"rp_id":"x","nonce":"AAEC","choices":["17","42","88"]}
        ]"""
        val fetch = assertIs<ChallengeFetch.Pending>(GatewayJson.challengeFetch(200, body))
        assertEquals(1, fetch.challenges.size)
        assertEquals("c1", fetch.challenges[0].id)
        assertEquals(listOf("17", "42", "88"), fetch.challenges[0].choices)
        assertEquals("vpn.deneme.example", fetch.challenges[0].rpId)
        assertIs<ChallengeFetch.Pending>(GatewayJson.challengeFetch(200, "[]"))
        assertEquals(ChallengeFetch.Rejected(401, "stale_timestamp"), GatewayJson.challengeFetch(401, """{"detail":{"code":"stale_timestamp"}}"""))
        assertIs<ChallengeFetch.Unreachable>(GatewayJson.challengeFetch(502, "<html>"))
        assertIs<ChallengeFetch.Unreachable>(GatewayJson.challengeFetch(200, "{}"))
    }

    @Test
    fun answerRequestAndResultFollowTheProtocol() {
        val request = Json.parseToJsonElement(GatewayJson.answerRequest("42", byteArrayOf(1, 2, 3))).jsonObject
        assertEquals("42", request["number"]!!.jsonPrimitive.content)
        assertEquals("AQID", request["signature"]!!.jsonPrimitive.content)
        assertEquals(AnswerResult.Answered, GatewayJson.answerResult(200, """{"id":"c1","status":"answered","reason":null}"""))
        assertEquals(AnswerResult.Refused("wrong_number"), GatewayJson.answerResult(200, """{"id":"c1","status":"refused","reason":"wrong_number"}"""))
        assertEquals(AnswerResult.Gone(409, "challenge_expired"), GatewayJson.answerResult(409, """{"detail":{"code":"challenge_expired"}}"""))
        assertEquals(AnswerResult.Gone(403, "token_disabled"), GatewayJson.answerResult(403, """{"detail":{"code":"token_disabled"}}"""))
        assertIs<AnswerResult.Unreachable>(GatewayJson.answerResult(503, ""))
    }
}
