package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        // A different local AP SSID is not an error: the configured credentials may describe an
        // access point this device joins as a client rather than this device's own SoftAP, which
        // is exactly the deployment the caller asked for. Only a matching local AP is validated
        // and read for channel/security; otherwise the configured network stays authoritative.
        val reportedApConfiguration = readApConfiguration()
        val apConfiguration = reportedApConfiguration?.takeIf { it.ssid == expectedSsid }
        if (reportedApConfiguration != null && apConfiguration == null) {
            onDiagnostic(
                "local AP SSID '${reportedApConfiguration.ssid}' differs from configured " +
                    "'$expectedSsid'; using the configured network",
            )
        }
        validateApConfiguration(apConfiguration)

        // A client associated to some other network, with no local AP carrying the configured
        // SSID, proves this device is a member of a different network from the one the peer was
        // told to join. Binding any local interface there advertises an address on the wrong
        // subnet, so the SoftAP fallback is withheld and the wait is reported as what it is.
        val onOtherNetwork = apConfiguration == null &&
            ManualHotspotStation.connectedSsid(appContext)
                ?.let { it != expectedSsid } == true

        var lastReason = "the configured network interface was not found"
        if (onOtherNetwork) {
            lastReason = "this device has not joined '$expectedSsid'"
        }
        var bridgeDeadlineNanos = 0L
        while (true) {
            check(!closed) { "ManualHotspotManager is closed" }
            // An external bridge that hands this device its network outranks everything: it is the
            // only interface the iPhone can reach, so the SoftAP must not be bound instead.
            val bridge = bridgeInterface()
            val station = stationInterface()
            val localInterface = when {
                bridge != null -> bridge
                station != null -> station
                onOtherNetwork -> null
                else -> findLocalHotspotInterface()
            }
            if (onOtherNetwork && bridge == null) {
                lastReason = "this device has not joined '$expectedSsid' (it is on " +
                    "'${ManualHotspotStation.connectedSsid(appContext) ?: "no network"}')"
            }

            // The adapter can enumerate after this device starts bringing the session up. Binding
            // the SoftAP in that window pins the whole session to an unreachable address, so the
            // fallback waits briefly for the bridge instead of settling on the first candidate.
            val bridgeStarting = bridge == null && UsbNetworkBridge.isPresent()
            if (bridgeStarting && bridgeDeadlineNanos == 0L) {
                bridgeDeadlineNanos = System.nanoTime() + BRIDGE_GRACE_NANOS
            }
            val waitingForBridge = bridgeStarting && System.nanoTime() < bridgeDeadlineNanos
            if (waitingForBridge) lastReason = "the USB bridge interface has no usable address yet"

            if (localInterface != null && !waitingForBridge) {
                val connectionFrequency = frequencyFromConnectionInfo()
                val scanFrequency = frequencyFromScanResult(localInterface)
                val channel = observedManualHotspotChannel(
                    apChannel = apConfiguration?.channel ?: 0,
                    connectionFrequencyMHz = connectionFrequency,
                    scanFrequencyMHz = scanFrequency,
                    apFrequencyMHz = apConfiguration?.frequencyMHz,
                )
                val frequencyMHz = when {
                    apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
                    connectionFrequency != null -> connectionFrequency
                    scanFrequency != null -> scanFrequency
                    else -> null
                }
                val security = apConfiguration?.security ?: expectedSecurity
                val path = when {
                    bridge != null -> "bridge"
                    station != null -> "station"
                    else -> "localAp"
                }
                onDiagnostic("Manual hotspot path=$path " +
                    "configReadable=${apConfiguration != null} " +
                    "security=$security channelKnown=${channel > 0} " +
                    "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
                    "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"}")
                if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
                    throw IOException("Manual hotspot is secured but no passphrase was provided")
                }

                if (channel == 0) {
                    Log.w(
                        TAG,
                        "Could not read the active hotspot channel from Android public APIs; " +
                            "reporting iAP2 channel 0 (auto) instead of configured channel " +
                            "$expectedChannel",
                    )
                }
                val observedBandLabel = wifiBandLabel(apConfiguration?.band)
                return WirelessHotspotInfo(
                    ssid = expectedSsid,
                    passphrase = passphrase,
                    security = security,
                    channel = channel,
                    frequencyMHz = frequencyMHz,
                    bssid = localInterface.hardwareAddress,
                    interfaceName = localInterface.name,
                    hostAddress = localInterface.hostAddress,
                    bandLabel = when (expectedBand) {
                        ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                        ManualHotspotBand.GHZ_5 -> "5 GHz"
                        ManualHotspotBand.AUTO ->
                            frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
                    },
                    backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
                )
            }

            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the manual hotspot: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw IOException(
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw IOException(
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw IOException(
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw IOException("Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw IOException("Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    /**
     * The interface an external bridge hands this device, or null when no bridge is carrying a
     * network.
     *
     * [findLocalHotspotInterface] excludes the active network because it targets this device's own
     * SoftAP, which is the opposite of what a bridge needs: the bridged interface often *is* the
     * active one, and it is the only address the iPhone can reach. Selecting it here is what lets
     * DiPlay host the session on the network the adapter provides.
     */
    private fun bridgeInterface(): LocalHotspotInterface? {
        val name = UsbNetworkBridge.liveInterface() ?: return null
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        return Collections.list(interfaces)
            .asSequence()
            .filter { it.name == name }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress?.toMacAddressString() }
                            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" },
                        // Outranks the SoftAP: only the bridged network reaches the iPhone.
                        score = interfaceScore(networkInterface.name, address) + BRIDGE_SCORE_BONUS,
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    /**
     * The interface carrying the configured network while this device is a client on it.
     *
     * That interface is the active network, which [findLocalHotspotInterface] deliberately
     * excludes because it targets this device's own SoftAP. An external access point needs the
     * opposite selection: without it DiPlay would advertise the SoftAP address the iPhone cannot
     * reach, or find no interface at all once the SoftAP is gone.
     */
    private fun stationInterface(): LocalHotspotInterface? {
        if (!ManualHotspotStation.isConnectedTo(appContext, expectedSsid)) return null
        val activeInterface = activeInterfaceName()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        return Collections.list(interfaces)
            .asSequence()
            .filter { networkInterface ->
                try {
                    !networkInterface.isLoopback && networkInterface.isUp &&
                        EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) } &&
                        (networkInterface.name == activeInterface ||
                            networkInterface.name.startsWith("wlan"))
                } catch (_: SocketException) {
                    false
                }
            }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress?.toMacAddressString() }
                            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" },
                        // Outranks every SoftAP candidate: only the configured network is usable.
                        score = interfaceScore(networkInterface.name, address) + STATION_SCORE_BONUS,
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    private fun activeInterfaceName(): String? = connectivityManager?.activeNetwork
        ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }

    private fun findLocalHotspotInterface(): LocalHotspotInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val primaryInterface = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        return Collections.list(interfaces)
            .asSequence()
            .filter { isUsableInterface(it, primaryInterface) }
            .mapNotNull { networkInterface ->
                networkInterface.hotspotAddress()?.let { address ->
                    LocalHotspotInterface(
                        name = networkInterface.name,
                        hostAddress = address,
                        hardwareAddress = runCatching { networkInterface.hardwareAddress?.toMacAddressString() }
                            .getOrNull()?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                            ?: HotspotInterfaceBssid.read(networkInterface.name),
                        score = interfaceScore(networkInterface.name, address),
                    )
                }
            }
            .maxByOrNull(LocalHotspotInterface::score)
    }

    private fun isUsableInterface(
        networkInterface: NetworkInterface,
        primaryInterface: String?,
    ): Boolean = try {
        networkInterface.name != primaryInterface &&
            !networkInterface.isLoopback &&
            networkInterface.isUp &&
            EXCLUDED_INTERFACE_PREFIXES.none { networkInterface.name.startsWith(it) }
    } catch (_: SocketException) {
        false
    }

    private fun interfaceScore(name: String, address: InetAddress): Int {
        var score = when {
            name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
            name.startsWith("p2p") -> 80
            name.startsWith("wlan") -> 70
            else -> 0
        }
        if (address is Inet4Address) {
            val bytes = address.address
            when {
                bytes[0] == 192.toByte() && bytes[1] == 168.toByte() -> score += 30
                address.isSiteLocalAddress -> score += 20
            }
        }
        if (address is Inet6Address && address.isLinkLocalAddress) score += 15
        return score
    }

    private fun NetworkInterface.hotspotAddress(): InetAddress? =
        wirelessHostAddress(Collections.list(inetAddresses), index)

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the manual hotspot", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
        val score: Int,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val STATION_SCORE_BONUS = 1_000
        const val BRIDGE_SCORE_BONUS = 1_000
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
        // Long enough for a USB adapter's network to come up, short enough not to delay a
        // deployment that has no adapter attached at all.
        val BRIDGE_GRACE_NANOS: Long = TimeUnit.SECONDS.toNanos(5)
        val EXCLUDED_INTERFACE_PREFIXES = listOf(
            "lo",
            "dummy",
            "rmnet",
            "r_rmnet",
            "tun",
            "ppp",
            "sit",
            "ip6",
            "bond",
        )
    }
}

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
