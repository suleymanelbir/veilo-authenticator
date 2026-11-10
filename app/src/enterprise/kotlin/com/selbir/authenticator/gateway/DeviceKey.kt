package com.selbir.authenticator.gateway

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.selbir.authenticator.core.VeiloKeyLevel
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * One EC P-256 device key per Gateway enrolment, generated in the Keystore with the ticket's
 * attestation challenge, so the Gateway can later (0.3) verify where the key lives and which
 * app made it. The private key never leaves the Keystore; it signs challenge-responses from 0.4.
 */
object DeviceKey {
    private const val KEYSTORE = "AndroidKeyStore"

    fun alias(accountId: String) = "veilo.device.$accountId"

    /** Tries the security chip first, then the TEE. */
    fun generate(alias: String, challenge: ByteArray, strongBoxAvailable: Boolean): DeviceKeyMaterial {
        if (strongBoxAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateInKeystore(alias, challenge, strongBox = true)
                return material(alias)
            } catch (e: StrongBoxUnavailableException) {
                delete(alias)
            } catch (e: Exception) {
                delete(alias)
            }
        }
        generateInKeystore(alias, challenge, strongBox = false)
        return material(alias)
    }

    /** Where the Keystore says the key lives, as the Gateway names levels. */
    fun level(alias: String): VeiloKeyLevel = try {
        val key = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> VeiloKeyLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> VeiloKeyLevel.TEE
                else -> VeiloKeyLevel.SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) VeiloKeyLevel.TEE else VeiloKeyLevel.SOFTWARE
        }
    } catch (e: Exception) {
        VeiloKeyLevel.SOFTWARE
    }

    /** ECDSA P-256 / SHA-256 with the Keystore private key, as the Gateway verifies it (§5). */
    fun signer(alias: String): DeviceSigner = DeviceSigner { payload ->
        val key = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as PrivateKey
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload)
            sign()
        }
    }

    fun delete(alias: String) {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            // nothing to do: the entry is gone or never existed
        }
    }

    private fun generateInKeystore(alias: String, challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private fun material(alias: String): DeviceKeyMaterial {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val chain: Array<Certificate> = store.getCertificateChain(alias) ?: emptyArray()
        val public = store.getCertificate(alias).publicKey as ECPublicKey
        return DeviceKeyMaterial(publicJwk = jwk(public), attestationChainPem = chain.map(::pem))
    }

    internal fun jwk(key: ECPublicKey): Map<String, String> = mapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to base64Url(fixedLength(key.w.affineX.toByteArray(), 32)),
        "y" to base64Url(fixedLength(key.w.affineY.toByteArray(), 32)),
    )

    /** BigInteger.toByteArray() may carry a sign byte or be short; JWK wants exactly 32 bytes. */
    internal fun fixedLength(bytes: ByteArray, length: Int): ByteArray {
        val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        require(trimmed.size <= length) { "coordinate too long" }
        return ByteArray(length - trimmed.size) + trimmed
    }

    internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    internal fun pem(certificate: Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
            "\n-----END CERTIFICATE-----"
}
