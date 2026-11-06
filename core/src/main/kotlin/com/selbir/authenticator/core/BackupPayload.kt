package com.selbir.authenticator.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The plaintext that goes inside a [BackupCodec] container: the accounts as JSON.
 *
 * ```
 * {"version":1,"accounts":[{"type":"TOTP","issuer":"…","name":"…","secret":"BASE32",
 *   "algorithm":"SHA1","digits":6,"period":30,"counter":0}]}
 * ```
 * Unknown fields are ignored on import, so later versions can add optional ones.
 */
object BackupPayload {
    private const val VERSION = 1

    @Serializable
    private class Document(val version: Int = VERSION, val accounts: List<Entry> = emptyList())

    @Serializable
    private class Entry(
        val type: OtpType,
        val issuer: String = "",
        val name: String = "",
        /** Base32, as services print it, so a decrypted backup can be read by other tools. */
        val secret: String,
        val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        val digits: Int = 6,
        val period: Int = 30,
        val counter: Long = 0,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The caller owns the returned array and should wipe it once it is encrypted. */
    fun encode(accounts: List<OtpSpec>): ByteArray {
        val document = Document(
            accounts = accounts.map {
                Entry(it.type, it.issuer, it.accountName, Base32.encode(it.secret), it.algorithm, it.digits, it.period, it.counter)
            },
        )
        return json.encodeToString(document).encodeToByteArray()
    }

    /** @throws BackupException if [plaintext] is not a payload this version understands. */
    fun decode(plaintext: ByteArray): List<OtpSpec> {
        try {
            val document = json.decodeFromString<Document>(plaintext.decodeToString())
            if (document.version != VERSION) throw unsupported()
            return document.accounts.map {
                OtpSpec(it.type, it.issuer, it.name, Base32.decode(it.secret), it.algorithm, it.digits, it.period, it.counter)
            }
        } catch (e: SerializationException) {
            // The serializer's message can quote the input, which is secret material: drop it.
            throw unsupported()
        } catch (e: IllegalArgumentException) {
            throw unsupported()
        }
    }

    private fun unsupported() = BackupException(BackupException.Reason.UNSUPPORTED, "Unsupported backup contents")
}
