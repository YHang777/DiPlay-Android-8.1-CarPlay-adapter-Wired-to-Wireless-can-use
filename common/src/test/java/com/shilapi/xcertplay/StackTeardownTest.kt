package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StackTeardownTest {
    private val steps = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    private fun teardown(
        closeController: () -> Unit = { steps += "closeController" },
        awaitControllerClosed: () -> Unit = { steps += "awaitControllerClosed" },
        closeSink: () -> Unit = { steps += "closeSink" },
        onComplete: () -> Unit = { steps += "onComplete" },
    ) = teardownCarPlayStack(
        closeController = closeController,
        awaitControllerClosed = awaitControllerClosed,
        closeSink = closeSink,
        reportFailure = { message, _ -> failures += message },
        onComplete = onComplete,
    )

    @Test
    fun everyStepRunsInOrderOnTheHappyPath() {
        teardown()

        assertEquals(
            listOf("closeController", "awaitControllerClosed", "closeSink", "onComplete"),
            steps,
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun completionStillRunsWhenAStepThrows() {
        // A stuck handshake latch is a permanent dead state, so onComplete must run unconditionally.
        teardown(closeController = { throw IllegalStateException("close failed") })

        assertEquals(listOf("onComplete"), steps.filter { it == "onComplete" })
        assertEquals(listOf("controller teardown failed"), failures)
    }

    @Test
    fun completionStillRunsWhenEveryStepThrows() {
        teardown(
            closeController = { throw IllegalStateException("1") },
            awaitControllerClosed = { throw IllegalStateException("2") },
            closeSink = { throw IllegalStateException("3") },
        )

        assertEquals(listOf("onComplete"), steps)
        assertEquals(
            listOf(
                "controller teardown failed",
                "controller close wait failed",
                "media sink teardown failed",
            ),
            failures,
        )
    }

    @Test
    fun aFailingCloseDoesNotSkipTheLaterSteps() {
        teardown(closeController = { throw IllegalStateException("close failed") })

        assertTrue(steps.contains("awaitControllerClosed"))
        assertTrue(steps.contains("closeSink"))
    }
}
