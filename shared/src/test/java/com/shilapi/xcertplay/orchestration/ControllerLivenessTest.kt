package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerLivenessTest {
    @Test
    fun freshControllerIsUsable() {
        assertTrue(ControllerLiveness().isUsable(closed = false))
    }

    @Test
    fun failedControllerIsUnusableUntilANewAttemptBegins() {
        val liveness = ControllerLiveness()
        liveness.markFailed()
        assertTrue(liveness.isFailed())
        assertFalse(liveness.isUsable(closed = false))

        liveness.beginAttempt()
        assertTrue(liveness.isUsable(closed = false))
    }

    @Test
    fun closedControllerIsNeverUsable() {
        val liveness = ControllerLiveness()
        assertFalse(liveness.isUsable(closed = true))

        liveness.markFailed()
        liveness.beginAttempt()
        assertFalse(liveness.isUsable(closed = true))
    }

    @Test
    fun repeatedIdenticalFailureIsAlwaysDelivered() {
        val first = CarPlayStatus.Failed("Bluetooth is not enabled")
        val repeated = CarPlayStatus.Failed("Bluetooth is not enabled")
        assertTrue(shouldReportStatus(first, repeated))
    }

    @Test
    fun changedStatusIsDelivered() {
        assertTrue(
            shouldReportStatus(CarPlayStatus.RunningWireless, CarPlayStatus.Failed("Bluetooth is not enabled")),
        )
    }

    @Test
    fun firstStatusIsDelivered() {
        assertTrue(shouldReportStatus(null, CarPlayStatus.RunningWireless))
    }

    @Test
    fun identicalNonFailureStatusIsDeduplicated() {
        assertFalse(shouldReportStatus(CarPlayStatus.RunningWireless, CarPlayStatus.RunningWireless))
    }

    @Test
    fun repeatedControlEndedIsDelivered() {
        assertTrue(shouldReportStatus(CarPlayStatus.ControlEnded, CarPlayStatus.ControlEnded))
    }

    @Test
    fun currentGenerationRunWithoutSessionFails() {
        assertTrue(
            shouldFailFinishedWirelessRun(
                closed = false,
                generation = 7,
                currentGeneration = 7,
                activeReported = false,
                handoffInProgress = false,
            ),
        )
    }

    @Test
    fun runSupersededByNewerGenerationStaysSilent() {
        assertFalse(
            shouldFailFinishedWirelessRun(
                closed = false,
                generation = 6,
                currentGeneration = 7,
                activeReported = false,
                handoffInProgress = false,
            ),
        )
    }

    @Test
    fun closedControllerStaysSilent() {
        assertFalse(
            shouldFailFinishedWirelessRun(
                closed = true,
                generation = 7,
                currentGeneration = 7,
                activeReported = false,
                handoffInProgress = false,
            ),
        )
    }

    @Test
    fun provenActiveSessionStaysSilent() {
        assertFalse(
            shouldFailFinishedWirelessRun(
                closed = false,
                generation = 7,
                currentGeneration = 7,
                activeReported = true,
                handoffInProgress = false,
            ),
        )
    }

    @Test
    fun handoffInFlightStaysSilent() {
        assertFalse(
            shouldFailFinishedWirelessRun(
                closed = false,
                generation = 7,
                currentGeneration = 7,
                activeReported = false,
                handoffInProgress = true,
            ),
        )
    }
}
