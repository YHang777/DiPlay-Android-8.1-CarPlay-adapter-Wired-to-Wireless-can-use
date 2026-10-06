package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/**
 * The configured manual network is not always this device's own SoftAP.
 *
 * It can also be an external access point this head unit joins as a Wi-Fi client, sharing that
 * network with the iPhone. On single-radio firmware the client interface and the SoftAP are
 * mutually exclusive, so once this device is associated the local hotspot no longer exists and
 * must not be demanded. Callers use [isConnectedTo] to tell those two deployments apart without
 * touching Wi-Fi state.
 */
object ManualHotspotStation {
    private const val ASSOCIATION_POLL_MILLIS = 250L

    /**
     * The outstanding peer-network request from [requestAssociation], kept for the life of the
     * association because Android tears the network down when the requester lets go.
     */
    private var associationRequest: ConnectivityManager.NetworkCallback? = null

    /**
     * Asks Android to move this device's Wi-Fi client onto [ssid], so the session runs on the
     * network the peer is already on instead of a local SoftAP the peer was never told to join.
     *
     * The configured manual network can be an access point that already exists rather than this
     * device's own SoftAP: the deployment where the network is provided externally and this device
     * only hosts the session on it. A client associated elsewhere sits on a different subnet from
     * the peer, so advertising it sends the iPhone to an address that never answers.
     *
     * Association is requested rather than forced, because Android owns the client radio. Returns
     * true when an attempt is under way and the caller may wait for it, false when nothing could
     * be requested at all, with [log] carrying the detail either way.
     */
    @Suppress("DEPRECATION")
    fun associate(
        context: Context,
        ssid: String,
        passphrase: String,
        log: (String) -> Unit = {},
    ): Boolean {
        if (ssid.isBlank()) {
            log("Wi-Fi join skipped: the configured hotspot SSID is empty")
            return false
        }
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifiManager == null) {
            log("Wi-Fi join skipped: this device has no Wi-Fi manager")
            return false
        }
        var requested = false
        val steps = mutableListOf<String>()
        // Android 10 and later only honour networks the app owns, so the network is registered as
        // this app's suggestion first. That is what makes Android join it on its own from now on,
        // including after the radio is toggled or the session is retried.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                    .build()
                val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    // A remembered network is joined by the framework whenever it considers it, so
                    // it counts as an attempt even when nothing is switching right now.
                    requested = true
                    steps += "remembered"
                } else {
                    steps += "suggestion refused ($status)"
                }
            } catch (failure: RuntimeException) {
                steps += "suggestion refused (${failure.message})"
            }
        }
        // The immediate attempt. Without it a suggestion only takes effect once the network the
        // device is on disappears, and the peer is waiting on this session now.
        val networkId = try {
            wifiManager.addNetwork(configuration(ssid, passphrase))
        } catch (failure: RuntimeException) {
            steps += "add failed (${failure.message})"
            -1
        }
        val legacyJoined = if (networkId < 0) {
            steps += "network rejected"
            false
        } else {
            steps += "saved #$networkId"
            val started = try {
                wifiManager.enableNetwork(networkId, true)
            } catch (failure: RuntimeException) {
                steps += "join failed (${failure.message})"
                false
            }
            if (started) {
                wifiManager.reconnect()
                steps += "joining now"
            } else {
                steps += "join refused"
            }
            started
        }
        // Android 10 and later refuse the call above to ordinary apps, so on a current device the
        // network is rejected and nothing joins. A network specifier is the supported way to ask,
        // and unlike a remembered suggestion it moves the radio now rather than waiting for the
        // network this device is on to disappear.
        if (!legacyJoined && requestAssociation(context, ssid, passphrase, steps, log)) {
            requested = true
        }
        log("Wi-Fi join '$ssid': ${steps.joinToString(", ")}")
        return requested
    }

    /**
     * Asks Android to associate through the platform's peer-network API.
     *
     * The request is kept registered on success: the association belongs to the requester and
     * is torn down when the last one lets go, so releasing it here would drop the network the
     * session is running on.
     */
    private fun requestAssociation(
        context: Context,
        ssid: String,
        passphrase: String,
        steps: MutableList<String>,
        log: (String) -> Unit,
    ): Boolean {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            steps += "no connection manager"
            return false
        }
        val request = try {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(
                    WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                        .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                        .build(),
                )
                .build()
        } catch (failure: RuntimeException) {
            steps += "join request refused (${failure.message})"
            return false
        }
        associationRequest?.let { previous ->
            try {
                connectivity.unregisterNetworkCallback(previous)
            } catch (_: RuntimeException) {
                // The framework may already have released it; nothing to undo either way.
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Joined Wi-Fi '$ssid' as a peer network")
            }

            override fun onUnavailable() {
                log("Wi-Fi join '$ssid': Android could not satisfy the request")
            }
        }
        return try {
            connectivity.requestNetwork(request, callback, Handler(Looper.getMainLooper()))
            associationRequest = callback
            steps += "asking Android to join"
            true
        } catch (failure: RuntimeException) {
            steps += "join request failed (${failure.message})"
            false
        }
    }

    /**
     * Waits up to [timeoutMillis] for this device to be associated to [ssid].
     *
     * Android completes association asynchronously and the session must not be advertised before
     * it lands, so the caller needs a definite answer rather than a best guess.
     */
    fun awaitConnected(context: Context, ssid: String, timeoutMillis: Long): Boolean {
        if (ssid.isBlank()) return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!isConnectedTo(context, ssid)) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(ASSOCIATION_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return isConnectedTo(context, ssid)
            }
        }
        return true
    }

    /**
     * SSIDs this device can currently see, or null when it is not allowed to report them.
     *
     * Only read on the failure path: a configured network that is not visible while others are
     * is a mistyped SSID, and naming what is actually in the air turns a session that refuses to
     * start into a one-line correction.
     */
    fun visibleSsids(context: Context): List<String>? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        val results = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            null
        } ?: return null
        return results.mapNotNull { result ->
            result.SSID?.takeUnless { it.isBlank() }
        }.distinct().sorted()
    }

    @Suppress("DEPRECATION")
    private fun configuration(ssid: String, passphrase: String): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = "\"$ssid\""
            // The default constructor still describes a WEP network, and a WPA2 network carrying
            // WEP group ciphers is rejected outright rather than silently downgraded. Every set is
            // reduced here to what a WPA2 network actually uses, and to nothing for an open one.
            allowedAuthAlgorithms.clear()
            allowedProtocols.clear()
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedKeyManagement.clear()
            allowedPairwiseCiphers.clear()
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.clear()
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            if (passphrase.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                preSharedKey = "\"$passphrase\""
            }
            status = WifiConfiguration.Status.ENABLED
        }

    /** SSID this device is currently associated to as a Wi-Fi client, or null when it is not. */
    fun connectedSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (connectionInfo.networkId < 0) return null
        unquote(connectionInfo.ssid)
            ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
            ?.let { return it }
        // Android withholds the SSID from callers whose location permission is in doubt while
        // still reporting which saved network is in use. Resolving that id against the saved
        // networks keeps "what am I connected to" answerable, and that answer is what decides
        // whether this device must join the configured network or already serves it.
        return try {
            wifiManager.configuredNetworks
                ?.firstOrNull { it.networkId == connectionInfo.networkId }
                ?.SSID
                ?.let(::unquote)
                ?.takeUnless { it.isBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    /** True when this device already serves [ssid] as a client, so no local hotspot is needed. */
    fun isConnectedTo(context: Context, ssid: String): Boolean =
        ssid.isNotBlank() && connectedSsid(context) == ssid

    /**
     * True while the Wi-Fi client radio is on.
     *
     * On single-radio firmware that radio being on means the SoftAP is deliberately off, and it
     * stays off until association completes. Callers must not demand the car hotspot during that
     * window: [isConnectedTo] is still false there, but the client is about to carry the session.
     */
    fun clientEnabled(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return false
        return try {
            wifiManager.isWifiEnabled
        } catch (_: SecurityException) {
            false
        }
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
