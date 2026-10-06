package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/**
 * A wireless CarPlay adapter plugged into this device's USB port hands the session a ready-made
 * network instead of this device hosting one: it bridges the Wi-Fi network the iPhone joins onto a
 * USB Ethernet function that appears here as `usb0`, `rndis0` or `ncm0`.
 *
 * That link is the session's real transport whenever it is live. The local SoftAP on the same
 * device sits on a different network from the iPhone's, so advertising it leaves the AirPlay
 * connection with nowhere to arrive and the session stuck at its opening state. Callers use
 * [isLive] and [isPresent] to tell this deployment apart from one where this device hosts its own
 * hotspot, without touching Wi-Fi state.
 */
object UsbNetworkBridge {
    private val BRIDGE_PREFIXES = listOf("usb", "rndis", "ncm")

    /** True when a USB Ethernet bridge is up and addressed, so an external network carries the session. */
    fun isLive(): Boolean = liveInterface() != null

    /**
     * True when the bridge interface exists but is not usable yet.
     *
     * The adapter has enumerated and its network is still coming up; a caller that binds the
     * SoftAP in that window pins the session to an address the iPhone cannot reach.
     */
    fun isPresent(): Boolean = interfaces().any { isBridgeName(it.name) }

    /** Name of the live, addressed bridge interface, or null when this device has no usable bridge. */
    fun liveInterface(): String? = interfaces()
        .firstOrNull { networkInterface ->
            try {
                isBridgeName(networkInterface.name) && networkInterface.isUp &&
                    !networkInterface.isLoopback &&
                    Collections.list(networkInterface.inetAddresses).any(::usableAddress)
            } catch (_: SocketException) {
                false
            }
        }
        ?.name

    fun isBridgeName(name: String): Boolean = BRIDGE_PREFIXES.any { name.startsWith(it) }

    private fun interfaces(): List<NetworkInterface> {
        val enumeration = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return emptyList()
        return Collections.list(enumeration)
    }

    private fun usableAddress(address: InetAddress): Boolean = when {
        address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress -> false
        address is Inet6Address -> address.isLinkLocalAddress || address.isSiteLocalAddress
        else -> true
    }
}
