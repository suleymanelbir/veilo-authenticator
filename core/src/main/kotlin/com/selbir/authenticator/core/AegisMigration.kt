package com.selbir.authenticator.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses Aegis Authenticator's plaintext JSON export format.
 *
 * Supports both version 1 (entries at root) and version 2 (entries inside `"db"`).
 * Encrypted Aegis vaults are not supported — the user must export as plaintext JSON.
 */
object AegisMigration {

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws IllegalArgumentException if [bytes] is not a valid Aegis export. */
    fun parse(bytes: ByteArray): List<OtpSpec> {
        val text = bytes.decodeToString()
        val document = try {
            json.decodeFromString<AegisDocument>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid Aegis export: ${e.message}")
        }

        val entries = document.db?.entries ?: document.entries ?: emptyList()
        require(entries.isNotEmpty()) { "No entries found in Aegis export" }

        return entries.mapNotNull { entry -> entryToSpec(entry) }
    }

    /** Checks whether [bytes] looks like an Aegis JSON export. */
    fun isAegisJson(bytes: ByteArray): Boolean = try {
        val text = bytes.decodeToString().trimStart()
        text.startsWith("{") && ("\"entries\"" in text || "\"db\"" in text)
    } catch (_: Exception) {
        false
    }

    private fun entryToSpec(entry: AegisEntry): OtpSpec? {
        val type = when (entry.type.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> return null  // Skip steam, yandex, etc.
        }

        val info = entry.info ?: return null
        val secret = try {
            Base32.decode(info.secret)
        } catch (_: Exception) {
            return null  // Skip entries with invalid secrets
        }

        if (secret.isEmpty()) return null

        return OtpSpec(
            type = type,
            issuer = entry.issuer.orEmpty().trim(),
            accountName = entry.name.trim(),
            secret = secret,
            algorithm = when (info.algo?.uppercase()?.replace("-", "")) {
                "SHA256" -> OtpAlgorithm.SHA256
                "SHA512" -> OtpAlgorithm.SHA512
                else -> OtpAlgorithm.SHA1
            },
            digits = info.digits ?: 6,
            period = info.period ?: 30,
            counter = info.counter ?: 0,
        )
    }

    // --- JSON model ---

    @Serializable
    private data class AegisDocument(
        val version: Int = 0,
        val db: AegisDb? = null,
        // Version 1 has entries at root level
        val entries: List<AegisEntry>? = null,
    )

    @Serializable
    private data class AegisDb(
        val entries: List<AegisEntry> = emptyList(),
    )

    @Serializable
    private data class AegisEntry(
        val type: String,
        val name: String = "",
        val issuer: String? = null,
        val info: AegisInfo? = null,
    )

    @Serializable
    private data class AegisInfo(
        val secret: String = "",
        val algo: String? = null,
        val digits: Int? = null,
        val period: Int? = null,
        val counter: Long? = null,
    )
}
