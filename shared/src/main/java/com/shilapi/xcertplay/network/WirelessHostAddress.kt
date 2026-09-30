package com.shilapi.xcertplay.network

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Use the same scoped, link-local path for manual APs as for Wi-Fi Direct.
 *
 * Vendor kernels may keep the link-local address on the AP interface but never install the
 * fe80::/64 route (Android 8/9 car units do this). Every IPv6 send then fails with ENETUNREACH,
 * the iPhone never gets an answer to its AirPlay connect, and the session hangs at "opening
 * CarPlay". The link-local is preferred only when the kernel can route that scope; otherwise the
 * interface's IPv4 address is used, which the phone can reach over the same L2.
 */
internal fun wirelessHostAddress(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    linkLocalRoutable: (Inet6Address) -> Boolean = ::isLinkLocalRoutable,
): InetAddress? {
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
            ?.let { Inet6Address.getByAddress(null, it.address, interfaceIndex) }
            ?.takeIf(linkLocalRoutable)
            ?.let { return it }
    }
    return addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
}

/**
 * Whether the kernel can route this link-local scope at all. A UDP `connect` is only a route
 * lookup: it sends nothing, and an unrouteable scope fails with ENETUNREACH.
 */
internal fun isLinkLocalRoutable(address: Inet6Address): Boolean {
    if (address.scopeId == 0) return false
    val probeTarget = Inet6Address.getByAddress(null, PROBE_TARGET, address.scopeId)
    return try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(probeTarget, PROBE_PORT))
            true
        }
    } catch (_: Exception) {
        false
    }
}

// Any neighbour in fe80::/64 other than the interface itself; connect() never contacts it.
private val PROBE_TARGET = ByteArray(16).also {
    it[0] = 0xfe.toByte()
    it[1] = 0x80.toByte()
    it[15] = 0x01
}
private const val PROBE_PORT = 9
