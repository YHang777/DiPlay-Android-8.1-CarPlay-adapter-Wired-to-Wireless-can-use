package com.shilapi.xcertplay.transport

import android.os.Build

/**
 * Upper bound for a buffer passed to [android.hardware.usb.UsbRequest.queue].
 *
 * Android 8.1 (API 27) rejects anything above 16 KiB with
 * "number of remaining bytes is out of range of [0, 16384] (too high)". API 28 removed that
 * check, so larger read chunks stay available on newer head units.
 */
internal val maxUsbRequestBytes: Int = if (Build.VERSION.SDK_INT < 28) 16_384 else Int.MAX_VALUE

/** Caps [preferred] to what [android.hardware.usb.UsbRequest.queue] accepts on this API level. */
internal fun usbRequestChunkBytes(preferred: Int): Int = minOf(preferred, maxUsbRequestBytes)
