package com.selbir.authenticator.core

import java.net.URLDecoder

/** Everything needed to enrol one OTP account. [secret] is the raw (decoded) shared secret. */
class OtpSpec(
    val type: OtpType,
    val issuer: String,
    val accountName: String,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
) {
    init {
        require(secret.isNotEmpty()) { "Secret is empty" }
        require(digits in Otp.MIN_DIGITS..Otp.MAX_DIGITS) { "Unsupported digit count" }
        require(period in 1..MAX_PERIOD_SECONDS) { "Unsupported period" }
        require(counter >= 0) { "Negative counter" }
    }

    private companion object {
        const val MAX_PERIOD_SECONDS = 3600
    }
}

/**
 * Parser for the `otpauth://` Key URI Format that enrolment QR codes carry:
 * `otpauth://totp/Issuer:account?secret=BASE32&issuer=Issuer&algorithm=SHA1&digits=6&period=30`
 */
object OtpAuthUri {
    private const val PREFIX = "otpauth://"

    /** @throws IllegalArgumentException if [text] is not a usable otpauth URI. */
    fun parse(text: String): OtpSpec {
        val uri = text.trim()
        require(uri.startsWith(PREFIX, ignoreCase = true)) { "Not an otpauth URI" }

        val rest = uri.substring(PREFIX.length).substringBefore('#')
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")

        val type = when (path.substringBefore('/').lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw IllegalArgumentException("Unsupported OTP type")
        }
        // '+' is a literal plus in a URI path; only the query uses it for a space.
        val label = decode(path.substringAfter('/', missingDelimiterValue = "").replace("+", "%2B"))
        val params = parseQuery(query)

        val labelIssuer = if (':' in label) label.substringBefore(':').trim() else ""
        val accountName = label.substringAfter(':').trim()

        val secret = Base32.decode(requireNotNull(params["secret"]) { "Missing secret" })
        return OtpSpec(
            type = type,
            issuer = params["issuer"]?.trim().orEmpty().ifEmpty { labelIssuer },
            accountName = accountName,
            secret = secret,
            algorithm = parseAlgorithm(params["algorithm"]),
            digits = params["digits"]?.let(::parseInt) ?: 6,
            period = params["period"]?.let(::parseInt) ?: 30,
            counter = params["counter"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Bad counter") } ?: 0,
        )
    }

    private fun parseAlgorithm(value: String?): OtpAlgorithm = when (value?.uppercase()?.replace("-", "")) {
        null, "", "SHA1" -> OtpAlgorithm.SHA1
        "SHA256" -> OtpAlgorithm.SHA256
        "SHA512" -> OtpAlgorithm.SHA512
        else -> throw IllegalArgumentException("Unsupported algorithm")
    }

    private fun parseInt(value: String): Int =
        value.toIntOrNull() ?: throw IllegalArgumentException("Bad numeric parameter")

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair -> decode(pair.substringBefore('=')).lowercase() to decode(pair.substringAfter('=', "")) }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
