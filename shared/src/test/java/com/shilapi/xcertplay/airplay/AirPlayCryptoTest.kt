package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = AirPlayCrypto.nonce64(0x0102030405060708L)
    private val aad = ByteArray(8) { (it + 3).toByte() }
    private val plaintext = ByteArray(97) { (it * 7).toByte() }

    @Test fun sealThenOpenRoundTrips() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        assertEquals(plaintext.size + 16, sealed.size)
        val opened = AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
        assertArrayEquals(plaintext, opened)
    }

    @Test fun sliceOpenMatchesTheWholeArrayOverload() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        // Padded on both sides so the offset overload has to honour offset/length exactly.
        val padded = ByteArray(3 + sealed.size + 5)
        sealed.copyInto(padded, 3)
        val opened = AirPlayCrypto.chachaOpen(key, nonce, sealed, 0, sealed.size, aad)
        val sliced = AirPlayCrypto.chachaOpen(key, nonce, padded, 3, sealed.size, aad)
        assertArrayEquals(plaintext, opened)
        assertArrayEquals(plaintext, sliced)
    }

    @Test fun tamperedTagIsRejected() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        val recovered = runCatching { AirPlayCrypto.chachaOpen(key, nonce, sealed, aad) }
        // Either path throws, or (platform cipher misbehaving) the plaintext must not come back.
        assert(recovered.isFailure || !recovered.getOrNull()!!.contentEquals(plaintext))
    }

    @Test fun wrongAadIsRejected() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val wrongAad = aad.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val recovered = runCatching { AirPlayCrypto.chachaOpen(key, nonce, sealed, wrongAad) }
        assert(recovered.isFailure || !recovered.getOrNull()!!.contentEquals(plaintext))
    }

    @Test fun openIntoWritesThePlaintextAndReturnsItsLength() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val dest = ByteArray(plaintext.size)
        val written = AirPlayCrypto.chachaOpenInto(key, nonce, sealed, 0, sealed.size, aad, dest)
        assertEquals(plaintext.size, written)
        assertArrayEquals(plaintext, dest)
    }

    @Test fun openIntoHonoursSliceOffsetAndDestOffset() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        // Ciphertext padded in front, destination padded in front: both offsets must be honoured.
        val padded = ByteArray(4 + sealed.size)
        sealed.copyInto(padded, 4)
        val dest = ByteArray(2 + plaintext.size + 3)
        val written = AirPlayCrypto.chachaOpenInto(
            key, nonce, padded, 4, sealed.size, aad, dest, 2,
        )
        assertEquals(plaintext.size, written)
        assertEquals(0, dest[0].toInt())
        assertEquals(0, dest[dest.size - 1].toInt())
        assertArrayEquals(plaintext, dest.copyOfRange(2, 2 + plaintext.size))
    }

    @Test fun openIntoRejectsATamperedTag() {
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        val dest = ByteArray(plaintext.size)
        val recovered = runCatching {
            AirPlayCrypto.chachaOpenInto(key, nonce, sealed, 0, sealed.size, aad, dest)
        }
        assert(recovered.isFailure || !dest.contentEquals(plaintext))
    }

    @Test fun openIntoRoundTripsRepeatedlyAcrossThreadLocalReuse() {
        // Exercises the per-thread cipher/engine reuse: alternating keys forces a re-key and
        // a long run exercises the cached SecretKeySpec / KeyParameter identity path.
        val otherKey = ByteArray(32) { (it * 3).toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        val sealedOther = AirPlayCrypto.chachaSeal(otherKey, nonce, plaintext, aad)
        val dest = ByteArray(plaintext.size)
        repeat(16) { round ->
            val useOther = round % 2 == 1
            val source = if (useOther) sealedOther else sealed
            val expectedKey = if (useOther) otherKey else key
            val written = AirPlayCrypto.chachaOpenInto(
                expectedKey, nonce, source, 0, source.size, aad, dest,
            )
            assertEquals(plaintext.size, written)
            assertArrayEquals(plaintext, dest)
        }
    }

    @Test fun repeatedSealWithAFixedLabelNonceDoesNotThrow() {
        // PairSetup MSG06 and PairVerify MSG02 retransmits seal more than once under the same
        // nonceLabel. Both JCE and BouncyCastle refuse to re-init a cached engine with a
        // key+nonce it has already sealed, so the fresh-instance retry is what keeps pairing
        // alive. This pins that path: without it a "cleanup" of the retry would throw
        // InvalidKeyException / IllegalArgumentException out of PairSetup and PairVerify.
        val labelNonce = AirPlayCrypto.nonceLabel("PS-Msg06")
        val first = AirPlayCrypto.chachaSeal(key, labelNonce, plaintext, aad)
        val second = AirPlayCrypto.chachaSeal(key, labelNonce, plaintext, aad)
        // AEAD is deterministic for a fixed key+nonce, so a real retry must reproduce the tag.
        assertArrayEquals(first, second)
        assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, labelNonce, first, aad))
    }

    @Test fun nonce64EncodesTheCounterLittleEndianAfterFourZeroBytes() {
        val nonce = AirPlayCrypto.nonce64(0x0102030405060708L)
        assertEquals(12, nonce.size)
        assertEquals(0, nonce[0].toInt())
        assertEquals(0, nonce[3].toInt())
        assertArrayEquals(
            byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            nonce.copyOfRange(4, 12),
        )
    }
}
