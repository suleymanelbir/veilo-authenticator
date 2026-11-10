package com.selbir.authenticator.gateway

import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.VeiloChallenge
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

    /** The reason code of a Gateway error body, `{"detail": {"code": ...}}`, or `http_<status>`. */
    private fun reasonCode(obj: JsonObject?, status: Int): String {
        val detail = obj?.get("detail")
        return (detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
            ?: (detail as? JsonPrimitive)?.contentOrNull
            ?: "http_$status"
    }

    private fun parseOrNull(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        null
    }

    /** `GET /device/challenges` (PROTOCOL.md §5). Malformed entries are skipped, not trusted. */
    fun challengeFetch(status: Int, body: String): ChallengeFetch = try {
        when {
            status == 200 -> ChallengeFetch.Pending(
                json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val choices = o["choices"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val nonce = o["nonce"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (choices.size != 3 || !choices.all { VeiloChallenge.NUMBER_FORMAT.matches(it) }) return@mapNotNull null
                    PendingChallenge(
                        id = id,
                        rpId = o["rp_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        nonce = nonce,
                        choices = choices,
                        expiresAt = o["expires_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            )
            status in 400..499 -> ChallengeFetch.Rejected(status, reasonCode(parseOrNull(body), status))
            else -> ChallengeFetch.Unreachable("http $status")
        }
    } catch (e: Exception) {
        ChallengeFetch.Unreachable("bad body: ${e.javaClass.simpleName}")
    }

    const val DEVICE_STATE_HEADER = "X-Veilo-Device-State"

    /** `X-Veilo-Device-State` value (PROTOCOL.md §5.1): base64url of a small JSON object. */
    fun deviceStateHeader(state: DeviceState): String = VeiloChallenge.base64Url(
        JsonObject(
            mapOf(
                "model" to JsonPrimitive(state.model),
                "app_version" to JsonPrimitive(state.appVersion),
                "patch_level" to (state.patchLevel?.let { JsonPrimitive(it) } ?: JsonNull),
                "managed" to JsonPrimitive(state.managed),
                "risks" to JsonArray(state.risks.map(::JsonPrimitive)),
            )
        ).toString().toByteArray()
    )

    /** `POST /device/challenges/{id}/response` body. */
    fun answerRequest(number: String, signature: ByteArray): String = JsonObject(
        mapOf("number" to JsonPrimitive(number), "signature" to JsonPrimitive(VeiloChallenge.base64Url(signature)))
    ).toString()

    fun answerResult(status: Int, body: String): AnswerResult {
        val obj = parseOrNull(body)
        return when {
            status == 200 && obj != null -> when (obj["status"]?.jsonPrimitive?.contentOrNull) {
                "answered" -> AnswerResult.Answered
                "refused" -> AnswerResult.Refused(obj["reason"]?.jsonPrimitive?.contentOrNull ?: "refused")
                else -> AnswerResult.Unreachable("bad body")
            }
            status in 400..499 -> AnswerResult.Gone(status, reasonCode(obj, status))
            else -> AnswerResult.Unreachable("http $status")
        }
    }

    @Suppress("unused")
    private fun JsonPrimitive.asBoolean() = boolean
}
