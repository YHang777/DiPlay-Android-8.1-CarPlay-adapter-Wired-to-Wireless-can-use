package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelKeyOutcomeTest {
    @Test
    fun wheelMediaKeysSendOnePressPerGesture() {
        for (keyCode in intArrayOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            val expected = CarPlayMediaButton.forKeyCode(keyCode)
            val down = wheelKeyOutcome(keyCode, KeyEvent.ACTION_DOWN, repeatCount = 0)
            assertTrue(down.consume)
            assertEquals(expected, down.mediaIndex)
        }
    }

    @Test
    fun bydPlayPauseIsAMediaKeyEvenThoughAndroidDoesNotRecognizeIt() {
        val outcome = wheelKeyOutcome(
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
            KeyEvent.ACTION_DOWN,
            repeatCount = 0,
        )
        assertTrue(outcome.consume)
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, outcome.mediaIndex)
    }

    @Test
    fun aHeldOrReleasedKeySendsNothingButStaysConsumed() {
        val held = wheelKeyOutcome(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, repeatCount = 1)
        assertTrue(held.consume)
        assertNull(held.mediaIndex)

        val released = wheelKeyOutcome(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, repeatCount = 0)
        assertTrue(released.consume)
        assertNull(released.mediaIndex)
    }

    @Test
    fun theVoiceKeyOpensSiriOnlyOnRelease() {
        val down = wheelKeyOutcome(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE, KeyEvent.ACTION_DOWN, 0)
        assertTrue(down.consume)
        assertFalse(down.siri)

        val up = wheelKeyOutcome(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE, KeyEvent.ACTION_UP, 0)
        assertTrue(up.consume)
        assertTrue(up.siri)
    }

    @Test
    fun volumeStaysWithTheSystemAndIsNeverLogged() {
        val outcome = wheelKeyOutcome(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0)
        assertFalse(outcome.consume)
        assertNull(outcome.mediaIndex)
        assertNull(outcome.unknownKeyCode)
    }

    @Test
    fun anUnknownKeyIsLoggedOnceThenLeftToTheSystem() {
        val keyCode = KeyEvent.KEYCODE_NAVIGATE_IN
        val first = wheelKeyOutcome(keyCode, KeyEvent.ACTION_DOWN, 0)
        assertFalse(first.consume)
        assertEquals(keyCode, first.unknownKeyCode)

        val second = wheelKeyOutcome(keyCode, KeyEvent.ACTION_DOWN, 0)
        assertFalse(second.consume)
        assertNull(second.unknownKeyCode)
    }
}
