package com.selbir.authenticator.data

import com.selbir.authenticator.core.HmacFunction
import com.selbir.authenticator.core.Otp
import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpSpec
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.security.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup export and import, against in-memory stand-ins for the file and the Keystore. */
class AccountRepositoryTest {
    private class MemoryAccountStore : AccountStore {
        var saved: List<Account> = emptyList()
        var failNextSave = false

        override fun load() = saved

        override fun save(accounts: List<Account>) {
            if (failNextSave) throw IllegalStateException("disk full")
            saved = accounts
        }
    }

    /** "Wraps" by XOR so that a stored secret is at least not the plaintext. */
    private class FakeSecretStore : SecretStore {
        val hardwareKeys = mutableMapOf<String, ByteArray>()
        var wrappingKeyDestroyed = false

        override fun importHmacKey(alias: String, secret: ByteArray, algorithm: OtpAlgorithm) {
            hardwareKeys[alias] = secret.copyOf()
        }

        override fun hmac(alias: String, algorithm: OtpAlgorithm): HmacFunction =
            Otp.softwareHmac(hardwareKeys.getValue(alias), algorithm)

        override fun wrap(secret: ByteArray) = xor(secret)

        override fun unwrap(blob: ByteArray): ByteArray {
            if (wrappingKeyDestroyed) throw java.security.InvalidKeyException("Key permanently invalidated")
            return xor(blob)
        }

        override fun delete(alias: String) {
            hardwareKeys.remove(alias)
        }

        private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
    }

    private val store = MemoryAccountStore()
    private val secrets = FakeSecretStore()
    private val repository = AccountRepository(store, secrets)

    private fun secret(seed: Int) = ByteArray(20) { (seed * 31 + it).toByte() }

    private fun totp(seed: Int, issuer: String = "Service $seed") =
        OtpSpec(OtpType.TOTP, issuer, "user@example.com", secret(seed))

    private fun hotp(seed: Int, counter: Long) = OtpSpec(
        type = OtpType.HOTP,
        issuer = "Counter $seed",
        accountName = "user",
        secret = secret(seed),
        algorithm = OtpAlgorithm.SHA256,
        digits = 8,
        counter = counter,
    )

    private fun newRepository() = AccountRepository(MemoryAccountStore(), FakeSecretStore())

    @Test
    fun exportedAccountsProduceTheSameCodesAfterImport() {
        val original = listOf(
            repository.add(totp(1), Protection.EXPORTABLE),
            repository.add(hotp(2, counter = 7), Protection.EXPORTABLE),
        )
        repository.nextHotpCode(original[1].id) // counter is now 8

        val other = newRepository()
        val result = other.importAll(repository.exportableSpecs())

        assertEquals(2, result.added)
        assertEquals(0, result.alreadyPresent)
        val restored = other.all()
        assertEquals(listOf("Service 1", "Counter 2"), restored.map { it.issuer })
        assertTrue(restored.all { it.protection == Protection.EXPORTABLE })
        assertEquals(repository.code(original[0], 55_555_555), other.code(restored[0], 55_555_555))
        // The HOTP counter travels with the backup, so the next code is not one already used.
        assertEquals(8, restored[1].counter)
        assertEquals(repository.nextHotpCode(original[1].id), other.nextHotpCode(restored[1].id))
    }

    @Test
    fun hardwareBoundAccountsAreNeverExported() {
        repository.add(totp(1), Protection.HARDWARE_BOUND)
        repository.add(totp(2), Protection.EXPORTABLE)

        assertEquals(listOf("Service 2"), repository.exportableSpecs().map { it.issuer })
    }

    @Test
    fun accountsWhoseWrappingKeyWasDestroyedAreLeftOut() {
        repository.add(totp(1), Protection.EXPORTABLE)
        secrets.wrappingKeyDestroyed = true

        assertTrue(repository.exportableSpecs().isEmpty())
    }

    @Test
    fun importingTheSameBackupTwiceAddsNothing() {
        repository.add(totp(1), Protection.EXPORTABLE)
        repository.add(totp(2), Protection.EXPORTABLE)
        val other = newRepository()
        other.importAll(repository.exportableSpecs())

        val again = other.importAll(repository.exportableSpecs())

        assertEquals(0, again.added)
        assertEquals(2, again.alreadyPresent)
        assertEquals(2, other.all().size)
    }

    @Test
    fun importSkipsKeysAlreadyHeldUnderAnotherNameOrInHardware() {
        repository.add(totp(1, issuer = "Renamed here"), Protection.EXPORTABLE)
        repository.add(totp(2), Protection.HARDWARE_BOUND)

        val result = repository.importAll(listOf(totp(1), totp(2), totp(3), totp(3)))

        assertEquals(1, result.added)
        assertEquals(3, result.alreadyPresent)
        assertEquals(listOf("Renamed here", "Service 2", "Service 3"), repository.all().map { it.issuer })
    }

    @Test
    fun sameSecretWithDifferentParametersIsADifferentAccount() {
        repository.add(totp(1), Protection.EXPORTABLE)
        val sixtySeconds = OtpSpec(OtpType.TOTP, "Service 1", "user@example.com", secret(1), period = 60)

        assertEquals(1, repository.importAll(listOf(sixtySeconds)).added)
    }

    @Test
    fun importIsAllOrNothing() {
        repository.add(totp(1), Protection.EXPORTABLE)
        store.failNextSave = true

        assertFailsWith<IllegalStateException> { repository.importAll(listOf(totp(2), totp(3))) }

        assertEquals(1, repository.all().size)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun secretsAreNotStoredInTheClear() {
        val account = repository.add(totp(1), Protection.EXPORTABLE)
        val imported = newRepository().apply { importAll(listOf(totp(2))) }.all().single()

        val plain1 = java.util.Base64.getEncoder().encodeToString(secret(1))
        val plain2 = java.util.Base64.getEncoder().encodeToString(secret(2))
        assertTrue(account.wrappedSecret != plain1 && imported.wrappedSecret != plain2)
        assertNull(repository.add(totp(3), Protection.HARDWARE_BOUND).wrappedSecret)
    }
}
