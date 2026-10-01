package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoYieldBudgetTest {
    @Test fun calmIsTodaysDecodeBudget() {
        val budget = VideoYieldBudget.forLevel(AudioPressure.CALM)
        assertEquals(60, budget.maxFrames)
        assertEquals(8 * 1024 * 1024, budget.maxBytes)
        assertEquals(250_000_000L, budget.maxFrameAgeNs)
        assertFalse(budget.dropNonKeyFrames)
        assertFalse(budget.softOverflow)
    }

    @Test fun elevatedKeepsEveryFrameButSoftensOverflow() {
        val budget = VideoYieldBudget.forLevel(AudioPressure.ELEVATED)
        assertEquals(30, budget.maxFrames)
        assertEquals(4 * 1024 * 1024, budget.maxBytes)
        assertEquals(150_000_000L, budget.maxFrameAgeNs)
        assertFalse(budget.dropNonKeyFrames)
        assertTrue(budget.softOverflow)
    }

    @Test fun criticalShrinksTheQueueAndDropsInterframes() {
        val budget = VideoYieldBudget.forLevel(AudioPressure.CRITICAL)
        assertEquals(15, budget.maxFrames)
        assertEquals(2 * 1024 * 1024, budget.maxBytes)
        assertEquals(80_000_000L, budget.maxFrameAgeNs)
        assertTrue(budget.dropNonKeyFrames)
        assertTrue(budget.softOverflow)
    }

    @Test fun unknownLevelsBehaveLikeCalm() {
        assertEquals(VideoYieldBudget.forLevel(AudioPressure.CALM), VideoYieldBudget.forLevel(-1))
    }
}
