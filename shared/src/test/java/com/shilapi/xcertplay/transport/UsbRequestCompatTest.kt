package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android 8.1 rejects UsbRequest.queue buffers above 16 KiB; larger chunk sizes must be capped. */
@RunWith(RobolectricTestRunner::class)
class UsbRequestCompatTest {
    @Test
    @Config(sdk = [27])
    fun capsChunkToApi27QueueLimit() {
        assertEquals(16_384, usbRequestChunkBytes(65_536))
        assertEquals(16_384, usbRequestChunkBytes(32 * 1024))
        assertEquals(512, usbRequestChunkBytes(512))
    }

    @Test
    @Config(sdk = [28])
    fun keepsPreferredChunkOnApi28() {
        assertEquals(65_536, usbRequestChunkBytes(65_536))
        assertEquals(32 * 1024, usbRequestChunkBytes(32 * 1024))
    }
}
