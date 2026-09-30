package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class MediaCodecSupportTest {
    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun adtsFramePinsTheHeaderLayout() {
        // AAC-LC, 44.1 kHz (index 4), stereo, 4-byte payload: frame length 11.
        assertArrayEquals(
            byteArrayOf(
                0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(),
                0x01, 0x7f, 0xfc.toByte(),
                0x21, 0x10, 0x56, 0xe5.toByte(),
            ),
            MediaCodecSupport.adtsFrame(byteArrayOf(0x21, 0x10, 0x56, 0xe5.toByte()), 44_100, 2),
        )
    }

    @Test
    fun writeAdtsHeaderFramesInPlaceLikeAdtsFrame() {
        val accessUnit = byteArrayOf(0x21, 0x10, 0x56, 0xe5.toByte())
        val scratch = ByteArray(MediaCodecSupport.ADTS_HEADER_BYTES + accessUnit.size)
        MediaCodecSupport.writeAdtsHeader(scratch, accessUnit.size, 44_100, 2)
        accessUnit.copyInto(scratch, MediaCodecSupport.ADTS_HEADER_BYTES)
        assertArrayEquals(MediaCodecSupport.adtsFrame(accessUnit, 44_100, 2), scratch)
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = (32 + index).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }
}
