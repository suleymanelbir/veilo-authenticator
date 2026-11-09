package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Wire format of `POST /enrol/confirm` (Gateway PROTOCOL.md §4 and protocol-vectors.json), kept free of I/O for tests. */
object GatewayJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun confirmRequest(
        enrolment: VeiloEnrolment,
        keyLevel: VeiloKeyLevel,
        app: AppIdentity,
        deviceKey: DeviceKeyMaterial,
        policySnapshot: Map<String, Any?>?,
    ): String = JsonObject(
        mapOf(
            "ticket" to JsonPrimitive(enrolment.ticket),
            "key_level" to JsonPrimitive(keyLevel.wire),
            "app" to JsonObject(
                mapOf(
                    "package" to JsonPrimitive(app.packageName),
                    "version" to JsonPrimitive(app.versionName),
                    "signing_digest" to JsonPrimitive(app.signingDigest),
                )
            ),
            "device_pubkey" to JsonObject(deviceKey.publicJwk.mapValues { JsonPrimitive(it.value) }),
            "attestation_chain" to JsonArray(deviceKey.attestationChainPem.map(::JsonPrimitive)),
            "policy_snapshot" to (policySnapshot?.let { snapshot ->
                JsonObject(
                    snapshot.mapValues { (_, v) ->
                        when (v) {
                            null -> JsonNull
                            is Boolean -> JsonPrimitive(v)
                            is Number -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    }
                )
            } ?: JsonNull),
        )
    ).toString()

    /** Maps an HTTP answer to a [ConfirmResult]; anything unexpected is [ConfirmResult.Unreachable]. */
    fun confirmResult(status: Int, body: String): ConfirmResult = try {
        val obj = json.parseToJsonElement(body).jsonObject
        when {
            status == 200 && obj["status"]?.jsonPrimitive?.contentOrNull == "recorded" -> ConfirmResult.Recorded(
                deviceId = obj["device_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                policyCompliant = obj["policy_compliant"]?.jsonPrimitive?.booleanOrNull ?: true,
                deviations = obj["policy_deviations"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            status in 400..499 -> {
                val detail = obj["detail"]
                val code = (detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: (detail as? JsonPrimitive)?.contentOrNull
                    ?: "http_$status"
                ConfirmResult.Refused(status, code)
            }
            else -> ConfirmResult.Unreachable("http $status")
        }
    } catch (e: Exception) {
        ConfirmResult.Unreachable("bad body: ${e.javaClass.simpleName}")
    }

    @Suppress("unused")
    private fun JsonPrimitive.asBoolean() = boolean
}
