package com.shilapi.xcertplay.airplay

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** BouncyCastle-backed primitives for the CarPlay pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val AEAD_TAG_BYTES = 16
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private val random = SecureRandom()
    private val EMPTY_AAD = ByteArray(0)
    private val CIPHER_TRANSFORMATIONS = listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = EMPTY_AAD,
    ): ByteArray {
        val dest = ByteArray(plaintext.size + AEAD_TAG_BYTES)
        chacha(encrypt = true, key, nonce, plaintext, 0, plaintext.size, aad, dest, 0)
        return dest
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = EMPTY_AAD,
    ): ByteArray = chachaOpen(key, nonce, ciphertextAndTag, 0, ciphertextAndTag.size, aad)

    /** Slice overload: decrypts a region instead of copying the sealed bytes first. */
    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray = EMPTY_AAD,
    ): ByteArray {
        // Degenerate input still fails with the same NegativeArraySizeException the
        // BouncyCastle fallback raised, before any provider is asked to look at it.
        val dest = ByteArray(length - AEAD_TAG_BYTES)
        chacha(encrypt = false, key, nonce, ciphertextAndTag, offset, length, aad, dest, 0)
        return dest
    }

    /**
     * Decrypts into [dest] and returns the plaintext length.
     *
     * [dest] must have room for `length - 16` bytes starting at [destOffset]. The audio receive
     * thread calls this with a pooled buffer so steady-state decryption allocates nothing:
     * no plaintext array, no key spec — only the per-packet nonce wrapper.
     * Authentication failures throw, exactly like [chachaOpen].
     */
    fun chachaOpenInto(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray,
        dest: ByteArray,
        destOffset: Int = 0,
    ): Int = chacha(
        encrypt = false,
        key = key,
        nonce = nonce,
        input = ciphertextAndTag,
        offset = offset,
        length = length,
        aad = aad,
        dest = dest,
        destOffset = destOffset,
    )

    private fun chacha(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray,
        dest: ByteArray,
        destOffset: Int,
    ): Int {
        val expected = if (encrypt) length + AEAD_TAG_BYTES else length - AEAD_TAG_BYTES
        if (expected < 0) {
            throw IllegalArgumentException("ChaCha20-Poly1305 input shorter than its 16-byte tag")
        }
        // Always BouncyCastle. The platform ChaCha20-Poly1305 path was a large speed win on
        // paper, but vendor JCE on head units has been observed to native-crash or mis-decrypt
        // under the AirPlay nonce/AAD layout — that showed up as a CarPlay session that died
        // seconds after the first media packet. Stability first; re-enable only with a device
        // gate that proves the transform.
        val written = bouncyCastleChaCha(encrypt, key, nonce, input, offset, length, aad, dest, destOffset)
        if (written != expected) {
            throw IllegalStateException("ChaCha20-Poly1305 wrote $written bytes, expected $expected")
        }
        return written
    }

    /**
     * RFC 8439 AEAD through the platform cipher when it exists (Conscrypt/native on Android 9+).
     *
     * BouncyCastle's ChaCha20Poly1305 is pure Java and is the single hottest per-packet cost on a
     * weak SoC: it runs on every audio datagram and every video frame. Returns null when the
     * platform refuses the transform so the caller can fall back.
     *
     * Failure classes are distinguished so a bad packet never costs a full BouncyCastle retry
     * forever, and a broken vendor transform stops being retried after its first failure:
     *  - [BadPaddingException] — a genuinely corrupt packet; BouncyCastle re-checks the tag so
     *    the caller still sees a single verdict path, exactly as before.
     *  - anything else (missing provider behaviour, rejected key/nonce, wrong output count) is
     *    structural: the platform cipher is disabled for this thread and every later packet goes
     *    straight to BouncyCastle instead of failing over per packet.
     */
    private fun platformChaCha(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray,
        dest: ByteArray,
        destOffset: Int,
    ): Int? {
        // Disabled: see chacha() — vendor ChaCha20-Poly1305 crashed sessions on the media path.
        return null
        @Suppress("UNREACHABLE_CODE")
        val holder = threadCipher.get() ?: return null
        val expected = if (encrypt) length + AEAD_TAG_BYTES else length - AEAD_TAG_BYTES
        return try {
            val cipher = holder.readyCipher(encrypt, key, nonce) ?: return null
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            val written = cipher.doFinal(input, offset, length, dest, destOffset)
            if (written == expected) written else {
                holder.value = null
                null
            }
        } catch (_: BadPaddingException) {
            null
        } catch (_: Exception) {
            holder.value = null
            null
        }
    }

    private fun bouncyCastleChaCha(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
        aad: ByteArray,
        dest: ByteArray,
        destOffset: Int,
    ): Int {
        // One engine per thread, re-keyed only when the key object changes: constructing a
        // fresh ChaCha20Poly1305 + KeyParameter on every packet was pure allocation when the
        // device has no platform ChaCha and BouncyCastle does the work.
        val state = bouncyState.get()!!
        val parameters = AEADParameters(state.keyFor(key), MAC_BITS, nonce, aad)
        try {
            state.cipher.init(encrypt, parameters)
        } catch (_: IllegalArgumentException) {
            // BouncyCastle refuses to re-init one engine for encryption with a key+nonce it
            // has already seen. Every call used to get a fresh engine, so a rejected repeat
            // gets one again instead of turning into an exception the callers never saw.
            val fresh = ChaCha20Poly1305()
            fresh.init(encrypt, parameters)
            state.cipher = fresh
        }
        val cipher = state.cipher
        // processBytes/doFinal are specified against getOutputSize, not against "plaintext
        // length". Writing straight into a tightly sized dest overflowed when the engine
        // reported a larger working output — one ArrayIndexOutOfBoundsException per packet
        // on the audio RX thread, which Android turns into a process crash. Stage through
        // a correctly sized scratch, then copy exactly what the engine produced.
        //
        // The scratch is thread-confined and grown on demand, not allocated per packet:
        // this runs on every audio datagram and every video frame, so a fresh array here
        // was hundreds of KB/s of young-generation garbage and the GC pauses showed up as
        // audio dropouts on a head unit with almost no free RAM.
        val scratch = state.scratchFor(maxOf(cipher.getOutputSize(length), 0))
        val processed = cipher.processBytes(input, offset, length, scratch, 0)
        val finalized = cipher.doFinal(scratch, processed)
        val total = processed + finalized
        if (total > dest.size - destOffset) {
            throw IllegalStateException(
                "ChaCha20-Poly1305 wrote $total bytes into a ${dest.size - destOffset}-byte window",
            )
        }
        System.arraycopy(scratch, 0, dest, destOffset, total)
        return total
    }

    /** Holder is never null so a failed probe is cached; only its [value] may be absent. */
    private class PlatformCipher(
        private val transformation: String?,
        @Volatile var value: Cipher?,
    ) {
        private var specKey: ByteArray? = null
        private var spec: SecretKeySpec? = null

        /** The stream key is fixed for the life of a stream, so the spec is built once. */
        fun keySpec(key: ByteArray): SecretKeySpec {
            if (specKey === key) return spec!!
            val fresh = SecretKeySpec(key, "ChaCha20")
            specKey = key
            spec = fresh
            return fresh
        }

        /**
         * Returns this thread's cipher initialised for [encrypt], or null when the provider is
         * structurally broken for this thread.
         *
         * SunJCE/Conscrypt refuse to initialise one instance for encryption twice with the same
         * key and nonce (InvalidKeyException). Every call used to get a brand-new engine, so a
         * rejected re-init is retried on a fresh instance before the provider is written off.
         */
        fun readyCipher(encrypt: Boolean, key: ByteArray, nonce: ByteArray): Cipher? {
            val current = value ?: return null
            val mode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
            val keySpec = keySpec(key)
            val iv = IvParameterSpec(nonce)
            try {
                current.init(mode, keySpec, iv)
                return current
            } catch (_: Exception) {
                // Fall through: one retry on a fresh instance distinguishes the providers'
                // nonce-reuse guard from a genuinely broken transform.
            }
            val fresh = if (transformation == null) {
                null
            } else {
                try {
                    Cipher.getInstance(transformation)
                } catch (_: Exception) {
                    null
                }
            }
            if (fresh == null) {
                value = null
                return null
            }
            return try {
                fresh.init(mode, keySpec, iv)
                value = fresh
                fresh
            } catch (_: Exception) {
                // A fresh instance failing too is structural: stop paying init costs per packet.
                value = null
                null
            }
        }
    }

    /**
     * Per-thread BouncyCastle state: the engine, the cached key parameter and the staging buffer.
     *
     * Thread-confined, so [scratchFor] needs no synchronisation and never hands the same array to
     * two callers. It only ever grows: the steady-state packet costs no allocation at all.
     */
    private class BouncyState(var cipher: ChaCha20Poly1305) {
        private var keyArg: ByteArray? = null
        private var keyParam: KeyParameter? = null
        private var scratch: ByteArray = EMPTY_AAD

        fun keyFor(key: ByteArray): KeyParameter {
            if (keyArg === key) return keyParam!!
            val fresh = KeyParameter(key)
            keyArg = key
            keyParam = fresh
            return fresh
        }

        fun scratchFor(size: Int): ByteArray {
            val current = scratch
            return if (current.size >= size) current else ByteArray(size).also { scratch = it }
        }
    }

    /**
     * Probed once for the whole process instead of once per thread: whether the platform ships
     * a ChaCha20-Poly1305 transform is a device property, not a thread property.
     */
    private val platformTransformation: String? = CIPHER_TRANSFORMATIONS.firstNotNullOfOrNull { transformation ->
        try {
            Cipher.getInstance(transformation)
            transformation
        } catch (_: Exception) {
            null
        }
    }

    private val threadCipher: ThreadLocal<PlatformCipher> = ThreadLocal.withInitial {
        val transformation = platformTransformation
        PlatformCipher(
            transformation,
            transformation?.let { name ->
                try {
                    Cipher.getInstance(name)
                } catch (_: Exception) {
                    null
                }
            },
        )
    }

    private val bouncyState: ThreadLocal<BouncyState> = ThreadLocal.withInitial {
        BouncyState(ChaCha20Poly1305())
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }

    /** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.asciiBytes()
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}
