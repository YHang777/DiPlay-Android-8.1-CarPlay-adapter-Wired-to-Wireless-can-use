package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectStartDecisionTest {
    @Test
    fun liveBackgroundSessionOpensProjectionDirectly() {
        assertTrue(shouldOpenProjectionDirectly(hasSession = true, controllerUsable = true))
    }

    @Test
    fun missingOrStaleSessionFallsBackToTheConnectGates() {
        assertFalse(shouldOpenProjectionDirectly(hasSession = false, controllerUsable = true))
        assertFalse(shouldOpenProjectionDirectly(hasSession = true, controllerUsable = false))
        assertFalse(shouldOpenProjectionDirectly(hasSession = false, controllerUsable = false))
    }

    @Test
    fun wirelessNeedsBothRadiosButWiredNeedsNeither() {
        assertTrue(radiosReadyForWireless(wirelessEnabled = false, bluetoothEnabled = false, wifiEnabled = false))
        assertTrue(radiosReadyForWireless(wirelessEnabled = true, bluetoothEnabled = true, wifiEnabled = true))
        assertFalse(radiosReadyForWireless(wirelessEnabled = true, bluetoothEnabled = false, wifiEnabled = true))
        assertFalse(radiosReadyForWireless(wirelessEnabled = true, bluetoothEnabled = true, wifiEnabled = false))
        assertFalse(radiosReadyForWireless(wirelessEnabled = true, bluetoothEnabled = false, wifiEnabled = false))
    }

    /**
     * Regression for the endless "Opening CarPlay…" spinner: a healthy wireless session reports
     * RunningWireless / WirelessActive, and those used to fall through friendlyStage's
     * "active"/"running" catch-all. They must have a fixed mapping so the label does not depend
     * on which locale's describe() text happens to contain those English words.
     */
    @Test
    fun liveWirelessStatusesNeverFallThroughToTheOpeningCarplayCatchAll() {
        val mapped = listOf(
            statusStageRes(CarPlayStatus.RunningWireless),
            statusStageRes(CarPlayStatus.WirelessActive),
            statusStageRes(CarPlayStatus.RunningControl),
            statusStageRes(CarPlayStatus.ControlEnded),
        )
        assertTrue(mapped.none { it == NO_STATUS_STAGE_RES })
        assertEquals("each status needs its own label", 4, mapped.toSet().size)
    }

    @Test
    fun failuresAndTransientStatusesKeepTheRawMessageLadder() {
        // Failed carries untranslated stack text (socket / RFCOMM / permission), which the ladder
        // is built to classify; a fixed mapping would throw that detail away.
        assertEquals(NO_STATUS_STAGE_RES, statusStageRes(CarPlayStatus.Failed("RFCOMM socket refused")))
        assertEquals(NO_STATUS_STAGE_RES, statusStageRes(CarPlayStatus.StartingHotspot))
        assertEquals(NO_STATUS_STAGE_RES, statusStageRes(CarPlayStatus.ConnectingBluetooth))
    }

    /**
     * Regression: this fleet links CarPlay over the head unit's own hotspot, which owns the
     * radio, so the Wi-Fi client STA reads off with every setting prepared. Requiring
     * isWifiEnabled parked the app on "Turn on Wi-Fi" and made connecting impossible.
     */
    @Test
    fun aHotspotOwningTheRadioStillCountsAsWiFiReady() {
        assertTrue(wifiReadyForWireless(clientEnabled = false, hotspotEnabled = true))
    }

    @Test
    fun aHiddenApStateMustNotBlockTheConnection() {
        // CarHotspotStatus returns null when the firmware hides AP state, and its contract is
        // that callers must not block on it.
        assertTrue(wifiReadyForWireless(clientEnabled = false, hotspotEnabled = null))
    }

    @Test
    fun onlyRadiosKnownOffBlockTheStart() {
        assertFalse(wifiReadyForWireless(clientEnabled = false, hotspotEnabled = false))
        assertTrue(wifiReadyForWireless(clientEnabled = true, hotspotEnabled = false))
        assertTrue(wifiReadyForWireless(clientEnabled = true, hotspotEnabled = null))
        assertTrue(wifiReadyForWireless(clientEnabled = true, hotspotEnabled = true))
    }
}
