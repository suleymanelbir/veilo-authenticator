package com.selbir.authenticator.data

import android.security.keystore.UserNotAuthenticatedException
import com.selbir.authenticator.core.Otp
import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpSpec
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.security.KeyLevel
import com.selbir.authenticator.security.SecretStore
import java.util.Base64
import java.util.UUID

class ImportResult(val added: Int, val alreadyPresent: Int)

/**
 * Enrolled accounts and code generation.
 *
 * Methods block (file and Keystore I/O) and must be called off the main thread.
 * Anything that uses a key can throw `UserNotAuthenticatedException` when the
 * Keystore's authentication window has lapsed; callers re-authenticate and retry.
 */
class AccountRepository(
    private val store: AccountStore,
    private val secrets: SecretStore,
) {
    private var accounts: List<Account>? = null

    @Synchronized
    fun all(): List<Account> = accounts ?: store.load().also { accounts = it }

    @Synchronized
    fun add(spec: OtpSpec, protection: Protection, group: String = ""): Account {
        val id = UUID.randomUUID().toString()
        if (protection == Protection.HARDWARE_BOUND) secrets.importHmacKey(keyAlias(id), spec.secret, spec.algorithm)
        val account = Account(
            id = id,
            type = spec.type,
            issuer = spec.issuer,
            name = spec.accountName,
            algorithm = spec.algorithm,
            digits = spec.digits,
            period = spec.period,
            counter = spec.counter,
            protection = protection,
            group = group,
            wrappedSecret = if (protection == Protection.EXPORTABLE) wrapSecret(spec.secret) else null,
        )
        try {
            persist(all() + account)
        } catch (e: Exception) {
            if (protection == Protection.HARDWARE_BOUND) secrets.delete(keyAlias(id))
            throw e
        }
        return account
    }

    /**
     * The decrypted contents of every backup-capable account, for writing a backup.
     * The caller must wipe each returned secret. Accounts whose wrapping key has been
     * destroyed are left out: there is nothing left to export.
     */
    fun exportableSpecs(): List<OtpSpec> {
        val specs = mutableListOf<OtpSpec>()
        try {
            for (account in all()) {
                if (account.protection != Protection.EXPORTABLE) continue
                val secret = try {
                    unwrapSecret(account)
                } catch (e: UserNotAuthenticatedException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                specs += OtpSpec(
                    type = account.type,
                    issuer = account.issuer,
                    accountName = account.name,
                    secret = secret,
                    algorithm = account.algorithm,
                    digits = account.digits,
                    period = account.period,
                    counter = account.counter,
                )
            }
        } catch (e: Exception) {
            specs.forEach { it.secret.fill(0) }
            throw e
        }
        return specs
    }

    /**
     * Adds [specs] as backup-capable accounts, all or nothing. An entry is skipped when an
     * account here (of either storage kind) or an earlier entry already holds the same key.
     */
    @Synchronized
    fun importAll(specs: List<OtpSpec>): ImportResult {
        val known = all().mapNotNullTo(HashSet(), ::fingerprint)
        val added = mutableListOf<Account>()
        for (spec in specs) {
            val probe = Otp.hotp(Otp.softwareHmac(spec.secret, spec.algorithm), PROBE_COUNTER, Otp.MAX_DIGITS)
            if (!known.add(KeyFingerprint(spec.type, spec.algorithm, spec.digits, spec.periodIfTimeBased(), probe))) continue
            added += Account(
                id = UUID.randomUUID().toString(),
                type = spec.type,
                issuer = spec.issuer,
                name = spec.accountName,
                algorithm = spec.algorithm,
                digits = spec.digits,
                period = spec.period,
                counter = spec.counter,
                protection = Protection.EXPORTABLE,
                wrappedSecret = wrapSecret(spec.secret),
            )
        }
        if (added.isNotEmpty()) persist(all() + added)
        return ImportResult(added = added.size, alreadyPresent = specs.size - added.size)
    }

    @Synchronized
    fun rename(id: String, issuer: String, name: String) {
        persist(all().map { if (it.id == id) it.copy(issuer = issuer, name = name) else it })
    }

    @Synchronized
    fun setGroup(id: String, group: String) {
        persist(all().map { if (it.id == id) it.copy(group = group) else it })
    }

    @Synchronized
    fun delete(id: String) {
        val account = all().find { it.id == id } ?: return
        persist(all() - account)
        if (account.protection == Protection.HARDWARE_BOUND) secrets.delete(keyAlias(id))
    }

    /**
     * Where the key that protects [account] is held: the account's own key when it is
     * hardware-bound, otherwise the key its secret is encrypted with.
     */
    fun keyLevel(account: Account): KeyLevel = when (account.protection) {
        Protection.HARDWARE_BOUND -> secrets.keyLevel(keyAlias(account.id))
        Protection.EXPORTABLE -> secrets.wrappingKeyLevel() ?: KeyLevel.UNKNOWN
    }

    /** The code for [counter]: a TOTP time step, or an HOTP counter value. */
    fun code(account: Account, counter: Long): String = code(account, counter, account.digits)

    private fun code(account: Account, counter: Long, digits: Int): String = when (account.protection) {
        Protection.HARDWARE_BOUND ->
            Otp.hotp(secrets.hmac(keyAlias(account.id), account.algorithm), counter, digits)

        Protection.EXPORTABLE -> {
            val secret = unwrapSecret(account)
            try {
                Otp.hotp(Otp.softwareHmac(secret, account.algorithm), counter, digits)
            } finally {
                secret.fill(0)
            }
        }
    }

    /** Generates the next HOTP code and advances the stored counter past it. */
    @Synchronized
    fun nextHotpCode(id: String): String {
        val account = all().first { it.id == id }
        val code = code(account, account.counter)
        persist(all().map { if (it.id == id) it.copy(counter = it.counter + 1) else it })
        return code
    }

    private fun persist(updated: List<Account>) {
        store.save(updated)
        accounts = updated
    }

    /**
     * Identifies the key an account holds without reading it, so that an import can spot
     * duplicates even among hardware-bound accounts: the code for a counter that no real
     * code ever uses. Null when the account's key is no longer usable.
     */
    private fun fingerprint(account: Account): KeyFingerprint? {
        val probe = try {
            code(account, PROBE_COUNTER, Otp.MAX_DIGITS)
        } catch (e: UserNotAuthenticatedException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val period = if (account.type == OtpType.TOTP) account.period else 0
        return KeyFingerprint(account.type, account.algorithm, account.digits, period, probe)
    }

    private data class KeyFingerprint(
        val type: OtpType,
        val algorithm: OtpAlgorithm,
        val digits: Int,
        val period: Int,
        val probe: String,
    )

    private fun OtpSpec.periodIfTimeBased() = if (type == OtpType.TOTP) period else 0

    private fun wrapSecret(secret: ByteArray): String = Base64.getEncoder().encodeToString(secrets.wrap(secret))

    /** The caller must wipe the returned array after use. */
    private fun unwrapSecret(account: Account): ByteArray {
        val wrapped = requireNotNull(account.wrappedSecret) { "Account has no stored secret" }
        return secrets.unwrap(Base64.getDecoder().decode(wrapped))
    }

    private fun keyAlias(id: String) = "otp.$id"

    private companion object {
        /** HOTP counters and TOTP time steps are never negative. */
        const val PROBE_COUNTER = -1L
    }
}
