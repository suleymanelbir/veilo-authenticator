package com.selbir.authenticator.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Device-to-device transfer over QR codes, with nothing leaving the two screens.
 *
 * 1. The receiving device makes a fresh P-256 key pair and shows its public key as one QR
 *    code ([receiverCode]).
 * 2. The sending device scans it, makes its own ephemeral key pair, agrees a key (ECDH, then
 *    HKDF-SHA-256) and encrypts the [BackupPayload] with AES-256-GCM ([encrypt]). The
 *    ciphertext is cut into numbered frames ([frames]) that the sender cycles through.
 * 3. The receiver scans frames in any order until a [FrameCollector] has them all, then
 *    [decrypt]s with its private key.
 *
 * Anyone filming the sender's screen gets ciphertext they cannot open: the key exists only in
 * the two devices' memory and is discarded when the transfer ends.
 */
object Transfer {
    private const val RECEIVER_PREFIX = "VTR1:"
    private const val FRAME_PREFIX = "VTF1:"
    private const val CURVE = "secp256r1"
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32
    private val INFO = "veilo-authenticator transfer v1".encodeToByteArray()

    /** Characters of ciphertext per frame: fits a QR code that a phone camera reads at arm's length. */
    const val FRAME_CHARS = 400
    private const val MAX_FRAMES = 500

    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64dec = Base64.getUrlDecoder()

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE), random)
            generateKeyPair()
        }

    /** The QR text the receiver shows. */
    fun receiverCode(receiverPublic: PublicKey): String = RECEIVER_PREFIX + b64.encodeToString(receiverPublic.encoded)

    /** @throws TransferException if [text] is not a receiver code carrying a valid P-256 key. */
    fun parseReceiverCode(text: String): PublicKey {
        val body = text.trim()
        if (!body.startsWith(RECEIVER_PREFIX)) throw TransferException("Not a transfer code")
        return decodePublicKey(decodeBase64(body.substring(RECEIVER_PREFIX.length)))
    }

    /**
     * Encrypts [plaintext] so that only the holder of the private half of [receiverPublic] can
     * read it. Returns `len (2) | sender public key | nonce (12) | ciphertext + tag`.
     */
    fun encrypt(plaintext: ByteArray, receiverPublic: PublicKey, random: SecureRandom = SecureRandom()): ByteArray {
        val sender = newKeyPair(random)
        val senderPublic = sender.public.encoded
        val receiverEncoded = receiverPublic.encoded
        val key = agreeKey(sender, receiverPublic, senderPublic, receiverEncoded)
        try {
            val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(senderPublic)
            cipher.updateAAD(receiverEncoded)
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(2 + senderPublic.size + NONCE_LEN + ciphertext.size)
                .putShort(senderPublic.size.toShort()).put(senderPublic).put(nonce).put(ciphertext)
                .array()
        } finally {
            key.fill(0)
        }
    }

    /** @throws TransferException if [blob] is malformed, altered, or was not meant for [receiver]. */
    fun decrypt(blob: ByteArray, receiver: KeyPair): ByteArray {
        if (blob.size < 2) throw TransferException("Transfer data is truncated")
        val buf = ByteBuffer.wrap(blob)
        val senderLen = buf.short.toInt()
        if (senderLen <= 0 || blob.size < 2 + senderLen + NONCE_LEN + TAG_BITS / 8) throw TransferException("Transfer data is truncated")
        val senderPublic = ByteArray(senderLen).also(buf::get)
        val nonce = ByteArray(NONCE_LEN).also(buf::get)
        val receiverEncoded = receiver.public.encoded
        val key = agreeKey(receiver, decodePublicKey(senderPublic), senderPublic, receiverEncoded)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(senderPublic)
            cipher.updateAAD(receiverEncoded)
            return cipher.doFinal(blob, buf.position(), blob.size - buf.position())
        } catch (e: GeneralSecurityException) {
            throw TransferException("Transfer data was altered or is not for this device", e)
        } finally {
            key.fill(0)
        }
    }

    /** Cuts an encrypted [blob] into the QR texts the sender shows: `VTF1:<tag>:<index>/<total>:<chunk>`. */
    fun frames(blob: ByteArray, frameChars: Int = FRAME_CHARS): List<String> {
        require(frameChars > 0)
        val text = b64.encodeToString(blob)
        val tag = tagOf(text)
        val chunks = text.chunked(frameChars)
        if (chunks.size > MAX_FRAMES) throw TransferException("Too much data to transfer at once")
        return chunks.mapIndexed { i, chunk -> "$FRAME_PREFIX$tag:${i + 1}/${chunks.size}:$chunk" }
    }

    /** Gathers the frames of one transfer, in any order, tolerating repeats and a restarted sender. */
    class FrameCollector {
        private var tag: String? = null
        private var chunks: Array<String?> = emptyArray()

        val total: Int get() = chunks.size
        val received: Int get() = chunks.count { it != null }

        /** @return true if [text] was a frame of a transfer and not one seen before. */
        fun accept(text: String): Boolean {
            val body = text.trim()
            if (!body.startsWith(FRAME_PREFIX)) return false
            val parts = body.substring(FRAME_PREFIX.length).split(':', limit = 3)
            if (parts.size != 3) return false
            val (frameTag, position, chunk) = parts
            val index = position.substringBefore('/').toIntOrNull() ?: return false
            val count = position.substringAfter('/', "").toIntOrNull() ?: return false
            if (count !in 1..MAX_FRAMES || index !in 1..count || chunk.isEmpty()) return false

            if (frameTag != tag || count != chunks.size) {
                tag = frameTag
                chunks = arrayOfNulls(count)
            }
            if (chunks[index - 1] != null) return false
            chunks[index - 1] = chunk
            return true
        }

        /**
         * The reassembled blob once every frame is in, else null.
         * @throws TransferException if the frames do not add up to what the sender showed.
         */
        fun assembled(): ByteArray? {
            if (chunks.isEmpty() || chunks.any { it == null }) return null
            val text = chunks.joinToString("")
            if (tagOf(text) != tag) throw TransferException("Frames belong to different transfers")
            return decodeBase64(text)
        }

        fun reset() {
            tag = null
            chunks = emptyArray()
        }
    }

    private fun tagOf(text: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())).substring(0, 6)

    private fun decodeBase64(text: String): ByteArray = try {
        b64dec.decode(text)
    } catch (e: IllegalArgumentException) {
        throw TransferException("Transfer data is garbled", e)
    }

    /** ECDH on P-256, then HKDF-SHA-256 with both public keys as salt. The caller wipes the result. */
    private fun agreeKey(own: KeyPair, peer: PublicKey, senderPublic: ByteArray, receiverPublic: ByteArray): ByteArray {
        val shared = try {
            KeyAgreement.getInstance("ECDH").run {
                init(own.private)
                doPhase(peer, true)
                generateSecret()
            }
        } catch (e: GeneralSecurityException) {
            throw TransferException("Key agreement failed", e)
        }
        try {
            return Hkdf.deriveSha256(ikm = shared, salt = receiverPublic + senderPublic, info = INFO, length = KEY_LEN)
        } finally {
            shared.fill(0)
        }
    }

    /**
     * Decodes an X.509 public key and checks that it is a point on P-256, so that a hostile
     * QR code cannot steer the key agreement onto a weaker curve.
     */
    private fun decodePublicKey(encoded: ByteArray): PublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey
        } catch (e: GeneralSecurityException) {
            null
        } ?: throw TransferException("Not a valid transfer key")
        if (!onP256(key)) throw TransferException("Not a valid transfer key")
        return key
    }

    /**
     * Taken from a generated key rather than `AlgorithmParameters.getInstance("EC")`, which
     * not every Android security provider offers.
     */
    private val p256: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    private fun onP256(key: ECPublicKey): Boolean {
        val curve = p256.curve
        if (key.params.curve != curve || key.params.generator != p256.generator || key.params.order != p256.order) return false
        val p = (curve.field as? java.security.spec.ECFieldFp)?.p ?: return false
        val x = key.w.affineX
        val y = key.w.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        // y^2 = x^3 + ax + b (mod p)
        val lhs = y.multiply(y).mod(p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(curve.a.multiply(x)).add(curve.b).mod(p)
        return lhs == rhs
    }
}

/** HKDF (RFC 5869) with HMAC-SHA-256. */
internal object Hkdf {
    fun deriveSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        try {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val n = minOf(previous.size, length - filled)
                System.arraycopy(previous, 0, out, filled, n)
                filled += n
                counter++
            }
            return out
        } finally {
            prk.fill(0)
        }
    }
}
