package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pick the manual-AP host address that wireless CarPlay advertises, binds and probes from.
 *
 * Manual hotspots are DHCPv4 networks: the iPhone reaches the accessory over IPv4, so the
 * AirPlay listener, the JmDNS bind and the connect probe must all use the AP's IPv4 address.
 * Preferring the scoped link-local instead drove interface mDNS onto the IPv6 group ff02::fb,
 * which never exchanged a single packet on API 27 SoftAP interfaces and left discovery stuck
 * at zero. Scoped link-local IPv6 remains the fallback when the AP has no usable IPv4.
 */
internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }?.let { return it }
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return null
}
