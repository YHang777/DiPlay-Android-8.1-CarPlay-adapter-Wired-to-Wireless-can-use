package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessHostAddressTest {
    @Test fun manualApPrefersScopedLinkLocalEvenWhenIpv4ComesFirst() {
        val addresses = listOf(ip("192.168.43.1"), ip("fe80::1234"))
        val result = wirelessHostAddress(addresses, 7, linkLocalRoutable = { true }) as Inet6Address
        assertTrue(result.isLinkLocalAddress)
        assertEquals(7, result.scopeId)
    }

    @Test fun replacesScopeFromAnotherInterface() {
        val wrongScope = Inet6Address.getByAddress(null, ip("fe80::1234").address, 3)
        val result = wirelessHostAddress(listOf(wrongScope), 8, linkLocalRoutable = { true })
        assertEquals(8, (result as Inet6Address).scopeId)
    }

    @Test fun fallsBackToIpv4WithoutUsableLinkLocal() {
        val ipv4 = ip("192.168.43.1")
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("::1"), ip("2001:db8::1"), ipv4), 7, linkLocalRoutable = { true }))
        assertEquals(ipv4, wirelessHostAddress(listOf(ip("fe80::1234"), ipv4), 0, linkLocalRoutable = { true }))
        assertNull(wirelessHostAddress(listOf(ip("0.0.0.0"), ip("127.0.0.1"), ip("224.0.0.251")), 7, linkLocalRoutable = { true }))
    }

    @Test fun fallsBackToIpv4WhenTheKernelCannotRouteLinkLocal() {
        // Android 8/9 car units keep fe80 on the AP interface but install no route for it.
        val ipv4 = ip("192.168.43.1")
        val addresses = listOf(ip("fe80::1234"), ipv4)
        assertEquals(ipv4, wirelessHostAddress(addresses, 7, linkLocalRoutable = { false }))
    }

    private fun ip(value: String) = InetAddress.getByName(value)
}
