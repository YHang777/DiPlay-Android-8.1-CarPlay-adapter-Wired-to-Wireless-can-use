package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessBringupWatchdogTest {
    private fun action(
        closed: Boolean = false,
        failed: Boolean = false,
        generation: Int = 3,
        currentGeneration: Int = 3,
        phaseIsWireless: Boolean = true,
        activeReported: Boolean = false,
        sessionProven: Boolean = false,
    ): WirelessBringupWatchdogAction = wirelessBringupWatchdogAction(
        closed = closed,
        failed = failed,
        generation = generation,
        currentGeneration = currentGeneration,
        phaseIsWireless = phaseIsWireless,
        activeReported = activeReported,
        sessionProven = sessionProven,
    )

    @Test
    fun firesOnlyWhenNothingCameUp() {
        assertEquals(WirelessBringupWatchdogAction.FAIL, action())
    }

    @Test
    fun preservesProvenSessionInsteadOfFailing() {
        assertEquals(WirelessBringupWatchdogAction.PRESERVE_SESSION, action(sessionProven = true))
    }

    @Test
    fun ignoresRunThatAlreadyReportedActive() {
        assertEquals(WirelessBringupWatchdogAction.IGNORE, action(activeReported = true))
    }

    @Test
    fun ignoresClosedController() {
        assertEquals(WirelessBringupWatchdogAction.IGNORE, action(closed = true))
    }

    @Test
    fun ignoresAlreadyFailedAttempt() {
        assertEquals(WirelessBringupWatchdogAction.IGNORE, action(failed = true))
    }

    @Test
    fun ignoresRunSupersededByNewerGeneration() {
        assertEquals(
            WirelessBringupWatchdogAction.IGNORE,
            action(generation = 3, currentGeneration = 4),
        )
    }

    @Test
    fun ignoresRunOutsideWirelessPhase() {
        assertEquals(WirelessBringupWatchdogAction.IGNORE, action(phaseIsWireless = false))
    }
}
