package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val USER_TIMEOUT_MILLIS = 45_000

    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        // Before 29, fromSocket owns the original descriptor rather than a duplicate.
        if (Build.VERSION.SDK_INT < 29) return
        try {
            ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                val fd = duplicate.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 10)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 3)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
                // Keepalive still reports a genuinely dead peer after ~19s. The write deadline is
                // longer on purpose: a brief Wi-Fi blip must not tear down the whole CarPlay
                // session, since rebuilding it costs a hotspot/BT renegotiation and reads as a
                // random disconnect to the driver.
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
                diagnostic("TCP peer health enabled idle=10s interval=3s count=3 deadline=${USER_TIMEOUT_MILLIS}ms; no video-idle timeout")
            }
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }
}
